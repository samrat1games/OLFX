# Gorilla Rush no Pico G2 / G2 4K

## O que este projeto é (e por que não há APK)

O ZIP recebido é um **jogo web** (HTML + CSS + JavaScript + three.js r128), não um projeto Android/Unity/Unreal.
A versão anterior era para o navegador do Oculus Go (WebVR 1.1 / WebXR). Portanto o "porte" correto é **web → navegador
do Pico G2**; não existe SDK, Gradle ou manifesto Android para compilar. Um APK que apenas embrulhasse a página em
uma WebView **não funcionaria em VR**: a WebView do Android não expõe WebVR/WebXR. Fazer um APK nativo (Pico SDK/Unity)
exigiria reescrever o jogo — não foi feito. Além disso, o Android SDK não pôde ser baixado no ambiente em que o
trabalho foi feito. Por isso **não há APK**; o entregável é o pacote web (`dist/GorillaRush-PicoG2-web.zip`).

## Hardware considerado (fontes públicas, não o headset físico)

| Item | Pico G2 4K | Pico G2 (3K) |
|---|---|---|
| SoC / GPU | Snapdragon 835 / Adreno 540 | idem |
| Tela | 3840×2160 (1920×2160 por olho), 75 Hz | 2880×1600 (1440×1600 por olho), 90 Hz |
| Rastreamento | 3DoF (cabeça) + 1 controle 3DoF (touchpad clicável, gatilho, App, Home) | idem |
| RAM | 4 GB | 4 GB |
| SO | Android (8.1 segundo ficha de varejo) + Pico OS | idem |

## Problemas reais encontrados no código para o Pico G2

1. **Detecção só conhecia Oculus**: `PLATFORM` só reconhecia `Pacific/OculusBrowser/Quest`. Num Pico (UA com "Android"/"Mobile")
   o jogo caía no ramo de **celular/Cardboard** (tela dividida por giroscópio + câmera/MediaPipe), nunca no VR do headset.
2. **Orçamento de pixels do Go**: a escala 0,8× era calibrada para 1280×1440 por olho (~1,2 Mpx). Com 1920×2160 por olho
   (4,1 Mpx) a mesma escala renderizaria ~3,5× mais pixels — inviável na Adreno 540 a 75 Hz.
3. **Mapeamento do controle fixo** ao layout do Go (botão 0 = touchpad, 1 = gatilho) e botão App só tratado para Gear VR.
4. **Modelo de locomoção 3DoF** era escolhido por `isGo`; num Pico G2 em WebXR cairia no caminho 6DoF (Quest).
5. **Textos/rotulagem** só falavam em Oculus Go.

## O que foi alterado (resumo; detalhes em `CHANGELOG_PICO_G2.md`)

- `js/platform.js`: detecção Pico (UA, nome do VRDisplay, `?device=pico`), resolução nativa real por olho, perfil 3DoF, novos ajustes de botões.
- `js/vr.js`: **escala por orçamento de pixels** (Baixa ≈ 1,2 Mpx como o Go; Média 1,6; Alta 2,2) → no G2 4K ≈ 1033×1162 por olho;
  canvas dimensionado **antes** de `requestPresent`; locomoção 3DoF e "sem câmera" para Pico; sem fallback Cardboard no Pico.
- `js/input.js`: botões configuráveis (clique do touchpad / gatilho / App) + botão App abre o menu; ids Pico/Goblin/Neo reconhecidos; diagnóstico de botões.
- UI: botão **ENTRAR EM VR (PICO G2)**, dicas e créditos atualizados; `index.html` ganhou favicon vazio (sem 404).
- `tools/`: `serve.py` (servidor local), `build.sh` (valida e empacota), `pico_test.py` (teste automatizado com Pico simulado).

## Instalação / uso no Pico G2

O jogo precisa ser aberto no **navegador do Pico** por uma URL **HTTPS** (ou `localhost`).

**A) Publicar (mais simples):** envie o conteúdo de `dist/GorillaRush-PicoG2/` para qualquer hospedagem HTTPS estática
(o repositório já tem o workflow do GitHub Pages: `.github/workflows/pages.yml`). Abra a URL no navegador do Pico
→ aba **JOGAR** → **ENTRAR EM VR (PICO G2)**.

**B) Local por USB (sem publicar):**
1. No PC: `python3 tools/serve.py` (porta 8000).
2. No Pico: ative as opções de desenvolvedor / depuração USB (o caminho exato varia por firmware) e conecte por USB.
3. No PC: `adb reverse tcp:8000 tcp:8000`.
4. No navegador do Pico abra `http://localhost:8000`. (`localhost` conta como contexto seguro.)

**Calibragem do controle (importante, ver limitações):** dentro do VR abra o menu (segure o touchpad 0,6 s) → **Dispositivo**.
O toast mostra o id do controle, o nº de botões e quais índices estão pressionados. Se gatilho/touchpad/App estiverem
trocados, ajuste em **Configurações → Botão: clique do touchpad / gatilho / Menu-App**.

## Verificação — o que foi e o que NÃO foi testado

1. **Verificado com sucesso por execução automatizada** (`tools/pico_test.py`, Google Chrome 153 headless + WebGL por software):
   carregamento do jogo; detecção Pico por UA e (separadamente) só pelo nome do VRDisplay; `ENTRAR EM VR (PICO G2)` habilitado;
   entrada em apresentação WebVR 1.1 simulada com olhos 1920×2160 → render **1033×1162 por olho**; 21+ `submitFrame`; pose da cabeça
   acompanhando o headset simulado; gatilho, botão App (abre/fecha o menu) e diagnóstico do controle; regressão do Oculus Go (render igual ao anterior).
   Todos os `js/*.js` passam em `node --check`.
2. **Verificado por análise de código/estática:** fluxo de escolha de API (WebXR > WebVR > tela), ausência de Cardboard/câmera no Pico, caminho 3DoF.
3. **NÃO testado no hardware físico do Pico G2.** Não foram medidos FPS, latência, conforto, nem o comportamento real do navegador do Pico.

## Limitações remanescentes (honestas)

- **Não verifiquei** se o navegador do firmware do seu G2 expõe WebVR 1.1 ou WebXR (relatos públicos são escassos e inconclusivos para o G2).
  Se não expuser nenhum, o botão de VR não aparece e **nenhuma página web conseguirá VR imersivo** nesse navegador; alternativa
  prática: instalar o navegador **Wolvic** (APK/Pico Store, suporta WebXR) se o firmware permitir, ou reescrever como app nativo (Pico SDK).
- **Layout real de botões do controle** do G2 no WebVR não foi confirmado (por isso os ajustes de botões e o diagnóstico).
  O botão **App/Back** pode ser consumido pelo sistema e nem chegar à página; o **Home** nunca chega (re-centraliza/sai).
- **Taxa de quadros** (75 Hz no 4K, 90 Hz no 3K) é decidida pelo navegador; os orçamentos de pixels são estimativas por analogia com o Go
  (Adreno 530 → 540), não medidas. Se houver queda de FPS, escolha Qualidade **Baixa**.
- **Texturas comprimidas** (ASTC/ETC2) não usadas: exigiria reconverter os assets e um transcoder.
- Multiplayer P2P continua via PeerJS (servidor público ou o de `server/`); depende de rede/NAT.
- 3DoF: sem posição real de cabeça/mãos; a locomoção usa o modelo de braço já existente.
