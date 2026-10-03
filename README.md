# Kinora para Android TV

Versão para **Android TV / Google TV** do [Kinora](https://github.com/cleitonsetti-debug/kinora-roku),
com a **mesma cara e a mesma interface** do canal Roku: tema escuro AMOLED, fonte Poppins, barra de navegação no topo,
banner do título em foco, posters arredondados com anel de foco branco, "Continuar assistindo" com barra de progresso,
busca com teclado próprio, filtros de catálogo/gênero, gerenciador de addons e ajustes em 3 idiomas.

Catálogo via **addons no protocolo Stremio** (padrão: Cinemeta), igual ao app Roku.

## Novidades da v1.3 (iguais ao Kinora v1.3 do Roku)

- **Player próprio:** Baixo (ou Cima/Menu) mostra os controles com ícones: -10 s, pausar/continuar, +10 s, ir para,
  legendas, áudio, próximo episódio e detalhes da fonte. Com os controles escondidos, OK pausa e Esquerda/Direita
  abrem a barra de tempo.
- **Barra de tempo:** Esquerda/Direita movem o ponto com passos que aceleram (10 a 120 s); OK confirma, Voltar cancela
  e, parado por 3 s, confirma sozinho.
- **Ficha ao iniciar:** título, nota do IMDb, classificação indicativa (quando o addon fornece), ano e gêneros por 7 s.
- **Legendas e áudio:** legendas do stream e de addons com o recurso `subtitles`, escolha de faixa de áudio e
  idiomas preferidos em Ajustes.
- **Próximo episódio:** cartão com contagem perto do fim e reprodução automática, preferindo a mesma fonte.
- **Destaques na tela inicial:** o banner gira sozinho (com fade) entre títulos em destaque, com botão **Detalhes**;
  a tecla Play abre o destaque. Linhas extras de filmes por gênero.
- **Addons:** painel com versão, descrição, tipos, recursos, catálogos e endereço do addon.
- **Sinopse:** buscada no addon quando falta no catálogo ("Sinopse indisponível." se o addon não tiver).

## Como gerar o APK

### Opção 1: GitHub Actions (sem instalar nada)
1. Crie um repositório (ex.: `kinora-androidtv`) e envie esta pasta:
   ```
   cd kinora-androidtv
   git init && git add . && git commit -m "Kinora para Android TV"
   git branch -M main
   git remote add origin https://github.com/SEU_USUARIO/kinora-androidtv.git
   git push -u origin main
   ```
2. Abra a aba **Actions** do repositório: o workflow **Gerar APK** roda sozinho.
3. Ao terminar, baixe o artefato **Kinora-AndroidTV** (um zip com o `Kinora-AndroidTV.apk`).
   Criando uma tag (`git tag v1.2.1 && git push --tags`) o APK também vai para a aba **Releases**.

### Opção 2: Android Studio
Abra a pasta no Android Studio (Ladybug ou mais novo), espere o Gradle sincronizar e use
**Build > Build App Bundle(s) / APK(s) > Build APK(s)**. Ou no terminal (com JDK 17 e Android SDK):
```
./gradlew assembleRelease
```
O APK sai em `app/build/outputs/apk/release/app-release.apk`.

## Instalar na TV

- **Pelo pendrive / app de arquivos:** copie o APK, abra com um gerenciador de arquivos na TV e permita
  "instalar apps desconhecidos" para ele.
- **Pelo app "Downloader"** (Google TV/Fire TV): baixe o APK pela URL da aba Releases.
- **Por ADB** (TV com depuração ativada em Opções do desenvolvedor):
  ```
  adb connect IP_DA_TV
  adb install -r Kinora-AndroidTV.apk
  ```

O app aparece na tela inicial da TV (banner na fileira de apps). Também funciona em celular/tablet na horizontal.

## Controle remoto

Tudo foi mapeado para o controle da Android TV, igual ao Roku:

| Roku | Android TV |
|---|---|
| Setas / OK / Voltar | Setas / OK (centro) / Voltar |
| `*` (opções) em Addons | **Menu**, ou **segurar OK** |
| Voltar-rápido / Avançar-rápido na busca | Retroceder / Avançar (ou Backspace / Espaço num teclado) |
| Play na busca | Play/Pause |

Na busca, um teclado físico ou USB também digita direto.

## Diferenças em relação ao Roku

- O player é o **Media3 / ExoPlayer**: toca MP4, MKV, HLS (`.m3u8`) e DASH (`.mpd`), com cabeçalhos HTTP do addon
  (`behaviorHints.proxyHeaders`). Os controles do player aparecem ao apertar qualquer seta/OK.
- Os dados (addons, ajustes, histórico) ficam no armazenamento do app; não são copiados do Roku.
- O layout foi desenhado em 1920x1080 (as mesmas coordenadas do Roku) e é escalado para qualquer resolução de TV.

## Estrutura

```
app/src/main/java/com/kinora/tv/
  MainActivity.kt
  data/   I18n (pt/en/es), AddonStore (protocolo Stremio), Net, Store (persistência), Models, Util
  ui/     App (pilha de telas), Theme (cores, Poppins, escala), Common (poster, pílulas, chips, foco)
          HomeScreen, SearchScreen, DetailsScreen, PlayerScreen, AddonsScreen, SettingsScreen, DialogView
app/src/main/res/font/   Poppins (SIL OFL)
```

## Avisos

O Kinora não inclui, hospeda nem indexa nenhum vídeo. As fontes vêm de addons que o próprio usuário adiciona,
e o usuário é responsável por assistir apenas conteúdo que tenha direito de ver. "Android TV" e "Google TV" são marcas
do Google LLC; "Stremio" pertence aos seus donos. O vídeo de teste é *Big Buck Bunny* (c) Blender Foundation, CC BY 3.0.

Código sob licença MIT (`LICENSE`); fonte Poppins sob SIL OFL 1.1 (`OFL-Poppins.txt`).
