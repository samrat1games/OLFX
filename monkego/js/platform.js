// Gorilla Rush (Pico G2 / Oculus Go) — detecção de dispositivo/capacidades + configurações persistentes.
// Carregado ANTES de core.js (não depende de THREE).
'use strict';
const PLATFORM=(function(){
  const ua=navigator.userAgent||'';
  // Oculus Go: codinome "Pacific" no UA do Oculus Browser. Quest = "Quest". Gear VR = "SamsungBrowser ... VR" / "Mobile VR".
  const isGo=/Pacific/i.test(ua)||/OculusGo/i.test(ua);
  const isOculus=/OculusBrowser/i.test(ua)||isGo||/Quest/i.test(ua);
  // Pico: o navegador do headset (Pico Browser) identifica-se com "Pico" no UA (ex.: modelo "Pico G2 4K").
  // Como o UA exato varia por firmware, a detecção é reforçada em P.ready pelo nome do VRDisplay/gamepad
  // e pode ser forçada com ?device=pico (testes). Pico Neo/Pico 4 são 6DoF: só a família G2/G3 usa o modelo 3DoF.
  let qdev='';try{qdev=(new URLSearchParams(location.search).get('device')||'').toLowerCase()}catch(e){}
  const isPico=/Pico/i.test(ua)||qdev==='pico'||qdev==='picog2';
  const isMobile=/Android|iPhone|iPad|iPod|Mobile/i.test(ua);
  const chrome=(ua.match(/Chrome\/(\d+)/)||[])[1]|0;
  let optChain=false;try{new Function('a','return a?.b');optChain=true}catch(e){}
  const P={ua,isGo,isOculus,isPico,isMobile,chrome,optChain,
    webxr:false,webxrChecked:false,webvr:!!navigator.getVRDisplays,vrDisplay:null,
    decompress:typeof window.DecompressionStream==='function',
    // dimensões nativas por olho (Pico G2 4K = 1920x2160; G2 3K = 1440x1600). Atualizado com o VRDisplay real.
    eyeNative:[1920,2160],
    gyroLikely:isMobile&&!isOculus&&!isPico};
  // Perfil de hardware: Adreno 540 (SD835) empurrando 4K => o custo de pixels é o gargalo, não a CPU.
  P.standalone=()=>P.isOculus||P.isPico;
  P.sixDoF=()=>/Neo|Pico ?4|Pico ?3|Pico4/i.test(ua)&&!/G2|G3/i.test(ua);
  P.threeDoFHeadset=()=>P.isGo||(P.isPico&&!P.sixDoF());
  P.ready=(async()=>{
    try{if(navigator.xr&&navigator.xr.isSessionSupported)P.webxr=!!(await navigator.xr.isSessionSupported('immersive-vr'))}catch(e){P.webxr=false}
    P.webxrChecked=true;
    if(P.webvr){try{const ds=await navigator.getVRDisplays();P.vrDisplay=(ds||[]).find(d=>d.capabilities&&d.capabilities.canPresent)||null}catch(e){P.vrDisplay=null}}
    if(P.vrDisplay){// nome real do display (confirma Pico mesmo com UA genérico) + resolução nativa por olho
      if(/pico/i.test(P.vrDisplay.displayName||''))P.isPico=true;
      try{const l=P.vrDisplay.getEyeParameters('left');if(l&&l.renderWidth>0&&l.renderHeight>0)P.eyeNative=[l.renderWidth,l.renderHeight]}catch(e){}}
    return P})();
  // Melhor API de VR disponível. "auto" = WebXR > WebVR 1.1 > Cardboard (celular) > tela normal.
  P.pickApi=pref=>{
    if(pref==='webxr')return P.webxr?'webxr':(P.vrDisplay?'webvr':'flat');
    if(pref==='webvr')return P.vrDisplay?'webvr':(P.webxr?'webxr':'flat');
    if(pref==='cardboard')return 'cardboard';
    if(pref==='flat')return 'flat';
    if(P.webxr)return 'webxr';if(P.vrDisplay)return 'webvr';
    return P.gyroLikely&&!P.isPico?'cardboard':'flat'};
  P.describe=()=>{const a=[];a.push(P.isPico?'Pico':isGo?'Oculus Go':isOculus?'Oculus Browser':isMobile?'Celular':'Desktop');
    a.push('WebXR: '+(P.webxr?'sim':'não'));a.push('WebVR 1.1: '+(P.vrDisplay?('sim ('+P.vrDisplay.displayName+')'):'não'));
    if(chrome)a.push('Chromium '+chrome);return a.join(' · ')};
  return P})();

// ---------- configurações (persistentes em localStorage) ----------
const SETTINGS=(function(){
  const D={impulse:2.2,maxSpeed:9,gravity:9.8,friction:5.5,sens:1.25,assist:.5,quality:-1,
    arm:1.32,simHand:3,style:'monke',color:'#5b3a24',smooth:.5,speedMode:'fast',music:.35,snap:1,names:1,handed:'auto',vrApi:'auto',map:'forest',mode:'solo',reach:1,padClick:0,padTrig:1,padApp:2};
  const S=Object.assign({},D);
  try{const j=JSON.parse(localStorage.getItem('monke_settings')||'{}');for(const k in D)if(k in j&&typeof j[k]===typeof D[k])S[k]=j[k]}catch(e){}
  // migração: quem já tinha configurações salvas ganha as novas mãos estáveis e braços longos
  try{if(localStorage.getItem('monke_settings_v')!=='2'){S.simHand=3;S.arm=Math.max(S.arm,1.32);localStorage.setItem('monke_settings_v','2')}}catch(e){}
  S.defaults=D;
  S.save=()=>{try{const o={};for(const k in D)o[k]=S[k];localStorage.setItem('monke_settings',JSON.stringify(o))}catch(e){}};
  S.reset=()=>{for(const k in D)S[k]=D[k];S.save()};
  return S})();

// Faixas/rótulos usados pelo menu VR e pelo lobby 2D (um único lugar = sem divergência).
const SETTING_DEFS=[
  {k:'speedMode',l:'Velocidade do movimento',opts:[['fast','Rápido (atual)'],['medium','Médio'],['slow','Lento']]},
  {k:'music',l:'Volume da música',min:0,max:1,step:.05,f:v=>v<=0?'Desligada':Math.round(v*100)+'%'},
  {k:'impulse',l:'Força do impulso',min:1,max:4,step:.1,f:v=>v.toFixed(1)+'x'},
  {k:'maxSpeed',l:'Velocidade máxima',min:4,max:16,step:.5,f:v=>v.toFixed(1)+' m/s'},
  {k:'gravity',l:'Gravidade',min:4,max:14,step:.2,f:v=>v.toFixed(1)},
  {k:'friction',l:'Atrito',min:1,max:10,step:.25,f:v=>v.toFixed(2)},
  {k:'sens',l:'Sensibilidade',min:.5,max:2.5,step:.05,f:v=>v.toFixed(2)+'x'},
  {k:'assist',l:'Assistência',min:0,max:1,step:.1,f:v=>Math.round(v*100)+'%'},
  {k:'arm',l:'Comprimento dos braços',min:1,max:1.45,step:.05,f:v=>v.toFixed(2)+'x'},
  {k:'quality',l:'Qualidade gráfica',opts:[[-1,'Auto'],[0,'Baixa (Go)'],[1,'Média'],[2,'Alta']]},
  {k:'simHand',l:'Duas mãos (1 controle)',opts:[[3,'Troca automática (estável)'],[1,'Alternada (atraso)'],[2,'Espelhada'],[0,'Só uma mão']]},
  {k:'smooth',l:'Estabilização do controle',min:0,max:1,step:.1,f:v=>v<=0?'Desligada':Math.round(v*100)+'%'},
  {k:'snap',l:'Giro no touchpad',opts:[[0,'Desligado'],[1,'Snap 30°'],[2,'Snap 45°']]},
  {k:'padClick',l:'Botão: clique do touchpad',opts:[[0,'Botão 0 (padrão)'],[1,'Botão 1'],[2,'Botão 2'],[3,'Botão 3'],[4,'Botão 4']]},
  {k:'padTrig',l:'Botão: gatilho',opts:[[0,'Botão 0'],[1,'Botão 1 (padrão)'],[2,'Botão 2'],[3,'Botão 3'],[4,'Botão 4']]},
  {k:'padApp',l:'Botão: Menu/App (abre menu)',opts:[[-1,'Nenhum'],[2,'Botão 2 (padrão)'],[3,'Botão 3'],[4,'Botão 4'],[0,'Botão 0'],[1,'Botão 1']]},
  {k:'handed',l:'Mão do controle',opts:[['auto','Auto'],['right','Direita'],['left','Esquerda']]},
  {k:'names',l:'Nomes dos jogadores',opts:[[1,'Mostrar'],[0,'Ocultar']]},
];
