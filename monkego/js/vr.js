// Gorilla Rush (Pico G2 / Oculus Go) — camada de apresentação VR.
//  • WebXR  (Oculus Browser 7+ / Pico Browser quando disponível): usa o WebXRManager do three r128 (R.xr).
//  • WebVR 1.1 (Oculus Browser 5/6 no Go, Pico G2 sem WebXR, e Go sem WebXR): implementação própria — o three r128 não
//    suporta mais WebVR, então aqui fazemos requestPresent, VRFrameData, projeção/visão por olho e submitFrame.
//  • Cardboard: caminho ORIGINAL do jogo (giroscópio + tela dividida em 2 vistas) — preservado.
//  • Tela normal: caminho original (arrastar para olhar / teclado).
// Nada aqui finge compatibilidade: cada modo só é oferecido se a API existir de fato (ver PLATFORM.ready).
'use strict';
const VRP=(function(){
  const V={mode:'flat',presenting:false,yaw:0,display:null,fd:null,xr:null,eyeW:0,eyeH:0,headPos:new THREE.Vector3()};
  const yawQ=new THREE.Quaternion(),_q=new THREE.Quaternion(),_v=new THREE.Vector3(),_m=new THREE.Matrix4(),_m2=new THREE.Matrix4(),_Yax=new THREE.Vector3(0,1,0);
  const eyeCam=new THREE.PerspectiveCamera();eyeCam.matrixAutoUpdate=false;
  // Escala do framebuffer por olho. Go (1280x1440): 0,8x => ~1,2 Mpx por olho. O Pico G2 4K tem 1920x2160 por
  // olho (4,1 Mpx): na mesma escala seria 3,5x mais pixels que o Go para uma Adreno 540 (~1,25x a 530), então
  // nos Pico a escala é limitada por ORÇAMENTO DE PIXELS por olho (Baixa ~1,2 Mpx, como o Go; Média 1,6; Alta 2,2).
  const PX_BUDGET=[1.2e6,1.6e6,2.2e6]; // por qualidade: baixa/média/alta (somente Pico)
  const scaleFor=()=>{const q=effQuality(),base=q<=0?.8:q==1?.9:1;
    if(!PLATFORM.isPico)return base;
    const w=PLATFORM.eyeNative[0],h=PLATFORM.eyeNative[1],hi=Math.max(0,Math.min(2,q<0?0:q));
    return Math.min(1,Math.sqrt(PX_BUDGET[hi]/(w*h)))};
  function effQuality(){return SETTINGS.quality<0?(PLATFORM.standalone()||PLATFORM.isMobile?0:1):SETTINGS.quality}
  V.effQuality=effQuality;
  V.setYaw=y=>{V.yaw=y;yawQ.setFromAxisAngle(_Yax,y)};
  V.turn=a=>V.setYaw(V.yaw+a);
  V.toWorldQuat=(raw,out)=>out.copy(yawQ).multiply(raw);
  // ---------- WebXR ----------
  async function startXR(){
    const s=await navigator.xr.requestSession('immersive-vr',{optionalFeatures:['local-floor']});
    R.xr.enabled=true;R.xr.setReferenceSpaceType('local');R.xr.setFramebufferScaleFactor(scaleFor());
    await R.xr.setSession(s);V.xr=s;V.mode='webxr';V.presenting=true;
    try{const L=s.renderState.baseLayer;if(L&&'fixedFoveation' in L)L.fixedFoveation=1}catch(e){}
    xrRig.add(R.xr.getController(0),R.xr.getController(1));
    const upd=()=>{INPUT.xrSources=Array.from(s.inputSources||[])};upd();s.addEventListener('inputsourceschange',upd);
    const sel=v=>e=>{const i=INPUT.xrSources.indexOf(e.inputSource);if(i>=0)INPUT.xrSelect[i]=v};
    s.addEventListener('selectstart',sel(true));s.addEventListener('selectend',sel(false));
    s.addEventListener('end',()=>{V.xr=null;R.setAnimationLoop(null);R.xr.enabled=false;xrRig.position.set(0,0,0);xrRig.rotation.set(0,0,0);onExit();requestAnimationFrame(frame)});
    R.setAnimationLoop(frame)}
  V.xrCtrlQuat=(i,out)=>{const c=R.xr.getController(i);if(!c)return false;out.copy(c.quaternion);return true};
  // ---------- WebVR 1.1 ----------
  function vrLoop(t){if(!V.display||!V.display.isPresenting)return;V.display.requestAnimationFrame(vrLoop);frame(t)}
  function sizeWebVR(){const d=V.display,l=d.getEyeParameters('left'),r=d.getEyeParameters('right'),s=scaleFor();
    V.eyeW=Math.round(Math.max(l.renderWidth,r.renderWidth)*s);V.eyeH=Math.round(Math.max(l.renderHeight,r.renderHeight)*s);
    R.setPixelRatio(1);R.setSize(V.eyeW*2,V.eyeH,false)}
  async function startWebVR(){
    const d=PLATFORM.vrDisplay;if(!d)throw 'sem VRDisplay';
    d.depthNear=.1;d.depthFar=cam.far;
    // dimensiona o canvas ANTES de apresentar: alguns navegadores (Pico/Oculus) fixam o tamanho da camada em requestPresent
    V.display=d;sizeWebVR();
    await d.requestPresent([{source:R.domElement}]);
    V.display=d;V.fd=new VRFrameData();V.mode='webvr';V.presenting=true;sizeWebVR();
    d.requestAnimationFrame(vrLoop)}
  addEventListener('vrdisplaypresentchange',()=>{if(V.mode==='webvr'&&V.display&&!V.display.isPresenting){V.display=null;onExit();requestAnimationFrame(frame)}});
  function onExit(){V.presenting=false;V.mode=V.fallback||'flat';vrView=V.mode==='cardboard';rs();
    if(state=='play'){MENU2D.show(true,'VR pausado. Toque em VOLTAR AO VR para continuar.')}}
  // pose da cabeça (WebVR): orientação 3DoF + modelo de pescoço do próprio navegador
  V.look=()=>{if(V.mode!=='webvr'||!V.display)return false;V.display.getFrameData(V.fd);const o=V.fd.pose&&V.fd.pose.orientation;
    if(o){_q.set(o[0],o[1],o[2],o[3]);cam.quaternion.copy(yawQ).multiply(_q)}
    const p=V.fd.pose&&V.fd.pose.position;if(p)V.headPos.set(p[0],p[1],p[2]);else V.headPos.set(0,0,0);return true};
  function renderEye(proj,view,x){
    // mundo_do_olho = T(P) * Ry(yaw) * T(-cabeça) * inverse(view)
    _m.fromArray(view).invert();
    _m2.makeTranslation(-V.headPos.x,-V.headPos.y,-V.headPos.z);_m.premultiply(_m2);
    _m2.makeRotationFromQuaternion(yawQ);_m.premultiply(_m2);
    _m2.makeTranslation(cam.position.x,cam.position.y,cam.position.z);_m.premultiply(_m2);
    eyeCam.matrix.copy(_m);eyeCam.matrixWorldNeedsUpdate=true;eyeCam.projectionMatrix.fromArray(proj);eyeCam.projectionMatrixInverse.copy(eyeCam.projectionMatrix).invert();
    R.setViewport(x,0,V.eyeW,V.eyeH);R.setScissor(x,0,V.eyeW,V.eyeH);R.render(S,eyeCam)}
  V.render=()=>{if(V.mode!=='webvr'||!V.display)return false;
    // pose re-amostrada no último instante = menor latência de rotação
    V.display.getFrameData(V.fd);const fd=V.fd;cam.updateMatrixWorld(true);
    R.setScissorTest(true);renderEye(fd.leftProjectionMatrix,fd.leftViewMatrix,0);renderEye(fd.rightProjectionMatrix,fd.rightViewMatrix,V.eyeW);R.setScissorTest(false);
    V.display.submitFrame();return true};
  // ---------- API pública ----------
  V.start=async function(pref){
    if(!PLATFORM.webxrChecked)await PLATFORM.ready;/* já resolvido no boot: mantém o gesto do usuário p/ requestPresent */const api=PLATFORM.pickApi(pref||SETTINGS.vrApi);V.fallback=PLATFORM.gyroLikely&&!PLATFORM.isPico?'cardboard':'flat';
    try{
      if(api==='webxr'){await startXR();return 'webxr'}
      if(api==='webvr'){await startWebVR();return 'webvr'}
    }catch(e){console.warn('VR',e);toast('Não foi possível entrar em VR ('+e+'). Usando modo '+(V.fallback==='cardboard'?'Cardboard':'tela')+'.')}
    V.mode=api==='cardboard'?'cardboard':(api==='flat'?'flat':V.fallback);V.presenting=false;vrOn=V.mode==='cardboard';vrView=vrOn&&state=='play';rs();return V.mode};
  V.resize=()=>{if(V.mode==='webvr'&&V.display)sizeWebVR()};
  V.stop=()=>{try{if(V.xr)V.xr.end();else if(V.display&&V.display.isPresenting)V.display.exitPresent()}catch(e){}};
  V.vrActive=()=>V.presenting&&(V.mode==='webxr'||V.mode==='webvr');
  // rig: posiciona câmera/rig na posição física P do macaco
  V.rig=()=>{if(V.mode==='webxr'&&R.xr.isPresenting){xrRig.rotation.set(0,V.yaw,0);_v.copy(cam.position).applyAxisAngle(_Yax,V.yaw);xrRig.position.copy(P).sub(_v);return true}
    xrRig.position.set(0,0,0);xrRig.rotation.set(0,0,0);cam.position.copy(P);return true};
  // 3DoF? (Go/Gear VR) — então a locomoção usa o modelo de braço próprio; em 6DoF (Quest) mantém o caminho WebXR original
  V.threeDoF=()=>V.mode==='webvr'||(V.mode==='webxr'&&(PLATFORM.threeDoFHeadset()||(!PLATFORM.sixDoF()&&!/quest|touch|index|vive|wmr|valve|neo|pico ?4/i.test(INPUT.ctrl.id))));
  V.headWorldQuat=out=>cam.getWorldQuaternion(out);
  // ---------- ganchos no núcleo ----------
  HOOK.rs=()=>V.mode==='webvr'&&V.presenting;
  HOOK.look=()=>V.look();
  HOOK.render=()=>V.render();
  HOOK.ownLoop=()=>V.mode==='webvr'&&V.presenting;
  HOOK.vrActive=()=>V.vrActive();
  HOOK.headTracked=()=>V.vrActive();
  HOOK.noCam=()=>V.vrActive()||PLATFORM.standalone()||INPUT.ctrl.present;
  HOOK.rig=()=>V.rig();
  HOOK.ctrlHands=()=>V.threeDoF();
  // lobby dentro do VR (apenas enquanto conecta/carrega): céu + HUD "carregando"
  HOOK.lobbyVR=dt=>{if(!V.vrActive())return false;dome.visible=true;S.fog=null;mapG&&(mapG.visible=!!mapDone);if(V.mode==='webvr')V.look();cam.position.copy(P.lengthSq()?P:spawnPos());
    HUD3D.set('mode','CARREGANDO…');HUD3D.update(dt);if(!V.render())R.render(S,cam);return true};
  return V})();
