# Monke Go (WebXR) - como abrir, sem servidor de hospedagem

O jogo esta em `web/` (HTML + JS + three.js). WebXR so funciona em `https://` ou `http://localhost`, entao ha 3 jeitos, nenhum usa host:

1. **APK (mais facil):** instale `dist/MonkeGo.apk` no headset (Pico G2/4/Neo 3, Oculus Go/Quest). O app serve o jogo em
   `http://localhost:8765` dentro do proprio headset e abre o navegador. Depois toque em **ENTRAR EM VR**.
2. **PC + cabo USB (adb):** `cd web && python3 -m http.server 8000`, depois `adb reverse tcp:8000 tcp:8000` e abra
   `http://localhost:8000` no navegador do headset.
3. **Wi-Fi:** igual ao 2, mas o endereco `http://IP-do-PC:8000` nao e "localhost" e o WebXR costuma ser bloqueado; use o 2.

Creditos do jogo (WelliDev e demais) em `README_GAME.md` e dentro do jogo. Notas do porte para Pico G2: `PICO_G2.md`.
