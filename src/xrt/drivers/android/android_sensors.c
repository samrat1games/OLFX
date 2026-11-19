// Copyright 2013, Fredrik Hultin.
// Copyright 2013, Jakob Bornecrantz.
// Copyright 2015, Joey Ferwerda.
// Copyright 2020-2025, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Android sensors driver code.
 * @author Lubosz Sarnecki <lubosz.sarnecki@collabora.com>
 * @author Korcan Hussein <korcan.hussein@collabora.com>
 * @author Simon Zeni <simon.zeni@collabora.com>
 * @ingroup drv_android
 */

#include "android_sensors.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "os/os_time.h"

#include "util/u_debug.h"
#include "util/u_device.h"
#include "util/u_distortion_mesh.h"
#include "util/u_var.h"
#include "util/u_visibility_mask.h"

#include "cardboard_device.pb.h"
#include "pb_decode.h"

#include "android/android_globals.h"
#include "android/android_content.h"
#include "android/android_custom_surface.h"

#include <xrt/xrt_config_android.h>

// Workaround to avoid the inclusion of "android_native_app_glue.h.
#ifndef LOOPER_ID_USER
#define LOOPER_ID_USER 3
#endif

// 60 events per second (in us).
#define POLL_RATE_USEC (1000L / 60) * 1000


DEBUG_GET_ONCE_LOG_OPTION(android_log, "ANDROID_SENSORS_LOG", U_LOGGING_WARN)

static inline struct android_device *
android_device(struct xrt_device *xdev)
{
	return (struct android_device *)xdev;
}

static bool
read_file(pb_istream_t *stream, uint8_t *buf, size_t count)
{
	U_LOG_E("read_file callback");
	FILE *file = (FILE *)stream->state;
	if (buf == NULL) {
		while (count-- && fgetc(file) != EOF)
			;
		return count == 0;
	}

	bool status = (fread(buf, 1, count, file) == count);

	if (feof(file)) {
		stream->bytes_left = 0;
	}

	return status;
}

static bool
read_buffer(pb_istream_t *stream, const pb_field_t *field, void **arg)
{
	U_LOG_E("read_file callback");
	uint8_t *buffer = (uint8_t *)*arg;
	return pb_read(stream, buffer, stream->bytes_left);
}

static bool
load_cardboard_distortion(struct android_device *d,
                          struct xrt_android_display_metrics *metrics,
                          struct u_cardboard_distortion_arguments *args)
{
	char external_storage_dir[PATH_MAX] = {0};
	if (!android_content_get_files_dir(android_globals_get_context(), external_storage_dir,
	                                   sizeof(external_storage_dir))) {
		ANDROID_ERROR(d, "failed to access files dir");
		return false;
	}

	/* TODO: put file in Cardboard folder */
	char device_params_file[PATH_MAX] = {0};
	snprintf(device_params_file, sizeof(device_params_file), "%s/current_device_params", external_storage_dir);

	FILE *file = fopen(device_params_file, "rb");
	if (file == NULL) {
		ANDROID_ERROR(d, "failed to open calibration file '%s'", device_params_file);
		return false;
	}

	pb_istream_t stream = {&read_file, file, SIZE_MAX, NULL};
	cardboard_DeviceParams params = cardboard_DeviceParams_init_zero;

	char vendor[64] = {0};
	params.vendor.arg = vendor;
	params.vendor.funcs.decode = read_buffer;

	char model[64] = {0};
	params.model.arg = model;
	params.model.funcs.decode = read_buffer;

	float angles[4] = {0};
	params.left_eye_field_of_view_angles.arg = angles;
	params.left_eye_field_of_view_angles.funcs.decode = read_buffer;

	params.distortion_coefficients.arg = args->distortion_k;
	params.distortion_coefficients.funcs.decode = read_buffer;

	if (!pb_decode(&stream, cardboard_DeviceParams_fields, &params)) {
		ANDROID_ERROR(d, "failed to read calibration file: %s", PB_GET_ERROR(&stream));
		return false;
	}

	if (params.has_vertical_alignment) {
		args->vertical_alignment = (enum u_cardboard_vertical_alignment)params.vertical_alignment;
	}

	if (params.has_inter_lens_distance) {
		args->inter_lens_distance_meters = params.inter_lens_distance;
	}
	if (params.has_screen_to_lens_distance) {
		args->screen_to_lens_distance_meters = params.screen_to_lens_distance;
	}
	if (params.has_tray_to_lens_distance) {
		args->tray_to_lens_distance_meters = params.tray_to_lens_distance;
	}

	args->fov = (struct xrt_fov){.angle_left = -DEG_TO_RAD(angles[0]),
	                             .angle_right = DEG_TO_RAD(angles[1]),
	                             .angle_down = -DEG_TO_RAD(angles[2]),
	                             .angle_up = DEG_TO_RAD(angles[3])};

	ANDROID_INFO(d, "loaded calibration for device %s (%s)", model, vendor);

	return true;
}

// Callback for the Android sensor event queue
static int
android_sensor_callback(ASensorEvent *event, struct android_device *d)
{
	struct xrt_vec3 gyro;
	struct xrt_vec3 accel;

	switch (event->type) {
	case ASENSOR_TYPE_ACCELEROMETER: {
		// Same permutation as the gyro below; a mirrored accel frame makes gravity correction fight the gyro.
		accel.x = -event->acceleration.y;
		accel.y = event->acceleration.x;
		accel.z = event->acceleration.z;

		ANDROID_TRACE(d, "accel %" PRId64 " %.2f %.2f %.2f", event->timestamp, accel.x, accel.y, accel.z);

		os_mutex_lock(&d->lock);
		d->last_accel = accel;
		d->have_accel = true;
		os_mutex_unlock(&d->lock);
		break;
	}
	case ASENSOR_TYPE_GYROSCOPE: {
		gyro.x = -event->data[1];
		gyro.y = event->data[0];
		gyro.z = event->data[2];

		ANDROID_TRACE(d, "gyro %" PRId64 " %.2f %.2f %.2f", event->timestamp, gyro.x, gyro.y, gyro.z);

		// Lock last_accel and the fusion.
		os_mutex_lock(&d->lock);

		if (d->have_accel) {
			m_imu_3dof_update(&d->fusion, event->timestamp, &d->last_accel, &gyro);
			d->have_gyro = true;
			struct timespec boot;
			if (clock_gettime(CLOCK_BOOTTIME, &boot) == 0) {
				int64_t boot_ns = (int64_t)boot.tv_sec * 1000000000LL + boot.tv_nsec;
				int64_t mono_ns = (int64_t)os_monotonic_get_ns();
				int64_t sample_ns = event->timestamp - (boot_ns - mono_ns);
				unsigned slot = d->history_next;
				d->history[slot].timestamp_ns = sample_ns;
				d->history[slot].orientation = d->fusion.rot;
				d->history_next = (slot + 1) % 512;
				if (d->history_count < 512) d->history_count++;
			}
		}

		// Now done.
		os_mutex_unlock(&d->lock);
		break;
	}
	default: ANDROID_TRACE(d, "Unhandled event type %d", event->type);
	}

	return 1;
}

static inline int32_t
android_get_sensor_poll_rate(const struct android_device *d)
{
	const float freq_multiplier = 1.0f / 3.0f;
	return (d == NULL) ? POLL_RATE_USEC
	                   : (int32_t)(d->base.hmd->screens[0].nominal_frame_interval_ns * freq_multiplier * 0.001f);
}

static void *
android_run_thread(void *ptr)
{
	struct android_device *d = (struct android_device *)ptr;
	const int32_t poll_rate_usec = android_get_sensor_poll_rate(d);
	// Maximum waiting time for sensor events.
	static const int max_wait_milliseconds = 100;
	ASensorManager *sensor_manager = NULL;
	const ASensor *accelerometer = NULL;
	const ASensor *gyroscope = NULL;
	ASensorEventQueue *event_queue = NULL;
#if __ANDROID_API__ >= 26
	sensor_manager = ASensorManager_getInstanceForPackage(XRT_ANDROID_PACKAGE);
#else
	sensor_manager = ASensorManager_getInstance();
#endif

	if (sensor_manager == NULL) {
		ANDROID_ERROR(d, "Android sensor manager unavailable");
		return NULL;
	}
	accelerometer = ASensorManager_getDefaultSensor(sensor_manager, ASENSOR_TYPE_ACCELEROMETER);
	gyroscope = ASensorManager_getDefaultSensor(sensor_manager, ASENSOR_TYPE_GYROSCOPE);
	if (accelerometer == NULL || gyroscope == NULL) {
		ANDROID_ERROR(d, "Head tracking requires both an accelerometer and a gyroscope");
		return NULL;
	}

	ALooper *event_looper = ALooper_forThread();
	if (event_looper == NULL) {
		event_looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
		ANDROID_INFO(d,
		             "Created new event event_looper for "
		             "sensor capture thread.");
	}

	event_queue = ASensorManager_createEventQueue(sensor_manager, event_looper, LOOPER_ID_USER, NULL, (void *)d);
	if (event_queue == NULL) {
		ANDROID_ERROR(d, "Could not create the sensor event queue");
		return NULL;
	}

	/*
	 * Start sensors in case this was not done already.
	 *
	 * On some Android devices, such as Pixel 4 and Meizu 20 series, running
	 * apps was not smooth due to the failure in setting the sensor's event
	 * rate. This was caused by the calculated sensor's event rate based on
	 * the screen refresh rate, which could be smaller than the sensor's
	 * minimum delay value. Make sure to set it to a valid value.
	 */
	if (accelerometer != NULL) {
		int32_t accelerometer_min_delay = ASensor_getMinDelay(accelerometer);
		int32_t accelerometer_poll_rate_usec = MAX(poll_rate_usec, accelerometer_min_delay);

		ASensorEventQueue_enableSensor(event_queue, accelerometer);
		ASensorEventQueue_setEventRate(event_queue, accelerometer, accelerometer_poll_rate_usec);
	}
	if (gyroscope != NULL) {
		int32_t gyroscope_min_delay = ASensor_getMinDelay(gyroscope);
		int32_t gyroscope_poll_rate_usec = MAX(poll_rate_usec, gyroscope_min_delay);

		ASensorEventQueue_enableSensor(event_queue, gyroscope);
		ASensorEventQueue_setEventRate(event_queue, gyroscope, gyroscope_poll_rate_usec);
	}

	while (os_thread_helper_is_running(&d->oth)) {
		int num_events = 0;
		const int looper_id = ALooper_pollOnce(max_wait_milliseconds, NULL, &num_events, NULL);
		// The device may have enabled a power-saving policy, causing the sensor to sleep and return
		// ALOOPER_POLL_ERROR. However, we want to continue reading data when it wakes up.
		if (looper_id == ALOOPER_POLL_TIMEOUT || looper_id == ALOOPER_POLL_WAKE) {
			continue;
		}
		if (looper_id != LOOPER_ID_USER) {
			ANDROID_ERROR(d, "Sensor looper failed: %d", looper_id);
			continue;
		}
		// Drain a batch instead of making one JNI/native call per sensor sample.
		ASensorEvent events[32];
		ssize_t count;
		while ((count = ASensorEventQueue_getEvents(event_queue, events, 32)) > 0) {
			for (ssize_t i = 0; i < count; i++) {
				android_sensor_callback(&events[i], d);
			}
		}
	}
	// Disable sensors.
	if (accelerometer != NULL) {
		ASensorEventQueue_disableSensor(event_queue, accelerometer);
	}
	if (gyroscope != NULL) {
		ASensorEventQueue_disableSensor(event_queue, gyroscope);
	}
	// Destroy the event queue.
	ASensorManager_destroyEventQueue(sensor_manager, event_queue);
	ANDROID_INFO(d, "android_run_thread exit");
	return NULL;
}


/*
 *
 * Device functions.
 *
 */

static void
android_device_destroy(struct xrt_device *xdev)
{
	struct android_device *android = android_device(xdev);

	// Destroy the thread object.
	os_thread_helper_destroy(&android->oth);

	// Now that the thread is not running we can destroy the lock.
	os_mutex_destroy(&android->lock);

	// Destroy the fusion.
	m_imu_3dof_close(&android->fusion);

	// Remove the variable tracking.
	u_var_remove_root(android);

	free(android);
}

static xrt_result_t
android_device_get_tracked_pose(struct xrt_device *xdev,
                                enum xrt_input_name name,
                                int64_t at_timestamp_ns,
                                struct xrt_space_relation *out_relation)
{
	struct android_device *d = android_device(xdev);

	struct xrt_space_relation new_relation = XRT_SPACE_RELATION_ZERO;

	os_mutex_lock(&d->lock);
	new_relation.pose.orientation = d->fusion.rot;
	// A camera image describes the head at exposure, not when inference completed.
	// Search newest-first and interpolate the two samples bracketing the requested time.
	for (unsigned n = 0; at_timestamp_ns > 0 && n < d->history_count; n++) {
		unsigned older = (d->history_next + 511 - n) % 512;
		if (d->history[older].timestamp_ns <= at_timestamp_ns || n + 1 == d->history_count) {
			new_relation.pose.orientation = d->history[older].orientation;
			if (n > 0 && d->history[older].timestamp_ns <= at_timestamp_ns) {
				unsigned newer = (older + 1) % 512;
				int64_t span = d->history[newer].timestamp_ns - d->history[older].timestamp_ns;
				if (span > 0) {
					float f = (float)(at_timestamp_ns - d->history[older].timestamp_ns) / (float)span;
					math_quat_slerp(&d->history[older].orientation, &d->history[newer].orientation,
					                f, &new_relation.pose.orientation);
				}
			}
			break;
		}
	}
	const bool tracked = d->sensors_available && d->have_accel && d->have_gyro;
	os_mutex_unlock(&d->lock);

	if (tracked) {
		new_relation.relation_flags = (enum xrt_space_relation_flags)(
		    XRT_SPACE_RELATION_ORIENTATION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT);
	}
	// This is a 3-DoF sensor device: never advertise a valid/tracked position.

	*out_relation = new_relation;
	return XRT_SUCCESS;
}


/*
 *
 * Prober functions.
 *
 */

static xrt_result_t
android_device_compute_distortion(
    struct xrt_device *xdev, uint32_t view, float u, float v, struct xrt_uv_triplet *result)
{
	struct android_device *d = android_device(xdev);
	if (d->sbs) {
		u_compute_distortion_identity(u, v, result);
		return XRT_SUCCESS;
	}
	if (view >= 2) {
		return XRT_ERROR_INPUT_UNSUPPORTED;
	}
	u_compute_distortion_cardboard(&d->cardboard.values[view], u, v, result);
	return XRT_SUCCESS;
}


struct android_device *
android_device_create(void)
{
	enum u_device_alloc_flags flags =
	    (enum u_device_alloc_flags)(U_DEVICE_ALLOC_HMD | U_DEVICE_ALLOC_TRACKING_NONE);
	struct android_device *d = U_DEVICE_ALLOCATE(struct android_device, flags, 1, 0);

	d->base.name = XRT_DEVICE_GENERIC_HMD;
	u_device_populate_function_pointers(&d->base, android_device_get_tracked_pose, android_device_destroy);
	d->base.get_view_poses = u_device_get_view_poses;
	d->base.get_visibility_mask = u_device_get_visibility_mask;
	d->base.compute_distortion = android_device_compute_distortion;
	d->base.inputs[0].name = XRT_INPUT_GENERIC_HEAD_POSE;
	d->base.device_type = XRT_DEVICE_TYPE_HMD;
	snprintf(d->base.str, XRT_DEVICE_NAME_LEN, "Android Sensors");
	snprintf(d->base.serial, XRT_DEVICE_NAME_LEN, "Android Sensors");

	d->log_level = debug_get_log_option_android_log();
	const char *sbs = getenv("XRT_COMPOSITOR_SBS");
	d->sbs = sbs != NULL && (strcmp(sbs, "1") == 0 || strcmp(sbs, "true") == 0);

	m_imu_3dof_init(&d->fusion, M_IMU_3DOF_USE_GRAVITY_DUR_20MS);

	int ret = os_mutex_init(&d->lock);
	if (ret != 0) {
		U_LOG_E("Failed to init mutex!");
		m_imu_3dof_close(&d->fusion);
		free(d);
		return NULL;
	}

	struct xrt_android_display_metrics metrics;
	if (!android_custom_surface_get_display_metrics(android_globals_get_vm(), android_globals_get_context(),
	                                                &metrics)) {
		U_LOG_E("Could not get Android display metrics.");
		/* Fallback to default values (Pixel 3) */
		metrics.width_pixels = 2960;
		metrics.height_pixels = 1440;
		metrics.density_dpi = 572;
		metrics.refresh_rate = 60.0f;
	}

	const float refresh_rate = metrics.refresh_rate > 0.0f ? metrics.refresh_rate : 60.0f;
	d->base.hmd->screens[0].nominal_frame_interval_ns = time_s_to_ns(1.0f / refresh_rate);

	const uint32_t w_pixels = metrics.width_pixels;
	const uint32_t h_pixels = metrics.height_pixels;

	const float angle = 45 * M_PI / 180.0; // 0.698132; // 40Deg in rads
	const float w_meters = ((float)w_pixels / (float)metrics.xdpi) * 0.0254f;
	const float h_meters = ((float)h_pixels / (float)metrics.ydpi) * 0.0254f;

	const struct u_cardboard_distortion_arguments cardboard_v1_distortion_args = {
	    .distortion_k = {0.441f, 0.156f, 0.f, 0.f, 0.f},
	    .screen =
	        {
	            .w_pixels = w_pixels,
	            .h_pixels = h_pixels,
	            .w_meters = w_meters,
	            .h_meters = h_meters,
	        },
	    .inter_lens_distance_meters = 0.06f,
	    .screen_to_lens_distance_meters = 0.042f,
	    .tray_to_lens_distance_meters = 0.035f,
	    .fov =
	        {
	            .angle_left = -angle,
	            .angle_right = angle,
	            .angle_up = angle,
	            .angle_down = -angle,
	        },
	    .vertical_alignment = U_CARDBOARD_VERTICAL_ALIGNMENT_BOTTOM,
	};
	struct u_cardboard_distortion_arguments args = cardboard_v1_distortion_args;
	if (!load_cardboard_distortion(d, &metrics, &args)) {
		ANDROID_WARN(
		    d, "Failed to load cardboard calibration file, falling back to Cardboard V1 distortion values");
		args = cardboard_v1_distortion_args;
	}

	u_distortion_cardboard_calculate(&args, d->base.hmd, &d->cardboard);

	// Distortion information.
	u_distortion_mesh_fill_in_compute(&d->base);

	ASensorManager *manager = ASensorManager_getInstanceForPackage(XRT_ANDROID_PACKAGE);
	d->sensors_available = manager != NULL &&
	    ASensorManager_getDefaultSensor(manager, ASENSOR_TYPE_ACCELEROMETER) != NULL &&
	    ASensorManager_getDefaultSensor(manager, ASENSOR_TYPE_GYROSCOPE) != NULL;

	os_thread_helper_init(&d->oth);
	if (d->sensors_available) {
		ret = os_thread_helper_start(&d->oth, android_run_thread, d);
		if (ret != 0) {
			ANDROID_ERROR(d, "Failed to start sensor thread; continuing without head tracking");
			d->sensors_available = false;
		}
	} else {
		ANDROID_WARN(d, "Gyroscope/accelerometer unavailable; reporting orientation tracking as unsupported");
	}

	u_var_add_root(d, "Android phone", true);
	u_var_add_log_level(d, &d->log_level, "log_level");
	u_var_add_ro_vec3_f32(d, &d->fusion.last.accel, "last.accel");
	u_var_add_ro_vec3_f32(d, &d->fusion.last.gyro, "last.gyro");

	d->base.supported.orientation_tracking = d->sensors_available;
	d->base.supported.position_tracking = false;

	ANDROID_DEBUG(d, "Created device!");

	return d;
}
