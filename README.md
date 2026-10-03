# Kinora para Android TV

Versão para **Android TV / Google TV** do [Kinora](https://github.com/cleitonsetti-debug/kinora-roku), com a
**mesma cara e a mesma interface** do canal Roku: tema escuro AMOLED, fonte Poppins, barra de navegação no topo,
banner com destaques, posters arredondados com anel de foco branco e player próprio.

O catálogo vem de **addons no protocolo Stremio** (padrão: Cinemeta), igual ao app Roku.

## Baixar

A versão mais recente está na aba [**Releases**](https://github.com/cleitonsetti-debug/kinora-androidtv/releases/latest).
Link direto do APK (serve para digitar no app Downloader da TV):

```
https://github.com/cleitonsetti-debug/kinora-androidtv/releases/latest/download/Kinora-AndroidTV.apk
```

## Instalar na TV

- **Pendrive / app de arquivos:** copie o APK, abra com um gerenciador de arquivos na TV (ex.: File Commander,
  X-plore) e permita "instalar apps desconhecidos" para ele.
- **App Downloader** (Google TV / Fire TV): digite o link direto acima.
- **ADB** (TV com depuração ativada em Opções do desenvolvedor):
  ```
  adb connect IP_DA_TV
  adb install -r Kinora-AndroidTV.apk
  ```

O Kinora aparece na fileira de apps da tela inicial. Também funciona em celular ou tablet na horizontal.

> **Atualizando da versão 1.2.x:** desinstale o app antigo antes de instalar a 1.3.0 (a chave de assinatura mudou).
> A partir da 1.3.0, as versões novas instalam por cima, sem desinstalar.

## O que o app faz

- **Início, Filmes e Séries** com linhas de catálogo dos addons ativos e "Continuar assistindo" com barra de progresso.
- **Destaques:** o banner gira sozinho (com fade) entre títulos em destaque, com o botão **Detalhes**;
  ao entrar nas linhas, ele acompanha o poster em foco. A tela inicial tem linhas extras de filmes por gênero.
- **Filtros** de catálogo e de gênero/ano em Filmes e Séries.
- **Busca** com teclado próprio na tela (um teclado físico ou USB também digita).
- **Detalhes:** sinopse, nota do IMDb, classificação indicativa (quando o addon fornece), temporadas e episódios.
- **Escolha da fonte de vídeo** entre os addons com o recurso `stream`, sempre com um vídeo de teste no fim da lista.
- **Player próprio:**
  - controles com ícones (-10 s, pausar, +10 s, ir para, legendas, áudio, próximo episódio e detalhes da fonte);
  - barra de tempo com passos que aceleram (10 a 120 s);
  - ficha de abertura com título, IMDb, classificação, ano e gêneros por 7 s;
  - legendas do stream e de addons com o recurso `subtitles`, troca de faixa de áudio;
  - próximo episódio automático, com cartão de contagem perto do fim.
- **Addons:** adicionar por URL (aceita `stremio://`), ativar/desativar, remover e ver os detalhes de cada um.
- **Ajustes** em português, inglês ou espanhol: retomar de onde parou, escolher a fonte automaticamente,
  idioma preferido da legenda e do áudio, próximo episódio automático, ficha ao iniciar, limpar o histórico e
  restaurar os addons padrão.

## Controle remoto

| Onde | Tecla | O que faz |
|---|---|---|
| Telas | Setas / OK / Voltar | Navegar, abrir, voltar |
| Tela inicial | Play | Abre o destaque do banner |
| Busca | Retroceder / Avançar | Apagar / espaço |
| Busca | Play | Ir para os resultados |
| Addons | Menu ou segurar OK | Remover o addon |
| Player (sem controles) | OK | Pausar / continuar |
| Player (sem controles) | Baixo, Cima ou Menu | Mostrar os controles |
| Player (sem controles) | Esquerda / Direita | Abrir a barra de tempo e mover |
| Player (com controles) | Esquerda / Direita, OK | Escolher e usar um botão |
| Player (com controles) | Cima | Barra de tempo |
| Barra de tempo | OK / Voltar | Confirmar / cancelar (parado por 3 s, confirma sozinho) |
| Player | Play, Retroceder, Avançar | Pausar, -30 s, +30 s |

## Diferenças em relação ao Roku

- O player usa o **Media3 / ExoPlayer**: toca MP4, MKV, HLS (`.m3u8`) e DASH (`.mpd`), com os cabeçalhos HTTP
  do addon (`behaviorHints.proxyHeaders`). Legendas externas em SRT, VTT ou SSA.
- O `*` do controle Roku virou **Menu** ou **segurar OK** na tela de Addons.
- Os dados (addons, ajustes, histórico) ficam no armazenamento do app; não são copiados do Roku.
- O layout usa as mesmas coordenadas do Roku (1920x1080) e é escalado para qualquer resolução de TV.

## Gerar o APK

### Pelo GitHub Actions
Todo `git push` na branch `main` roda o workflow **Gerar APK**. O APK fica em **Actions → execução → Artifacts**.

### Publicar uma versão (Release)
1. Suba o número da versão em `app/build.gradle.kts` (`versionCode` +1 e `versionName`).
2. Faça o commit e envie:
   ```
   git push
   git tag v1.3.1
   git push origin v1.3.1
   ```
3. O Actions cria a Release `v1.3.1` com o `Kinora-AndroidTV.apk` anexado.

### No computador
Com JDK 17 e Android SDK (ou pelo Android Studio):
```
./gradlew assembleRelease
```
O APK sai em `app/build/outputs/apk/release/app-release.apk`.

O APK é assinado com a chave `app/kinora.jks`, que fica no repositório só para permitir instalar por fora da loja.
Para publicar na Play Store, use uma chave própria e guarde-a fora do repositório.

## Estrutura

```
app/src/main/java/com/kinora/tv/
  MainActivity.kt
  data/   I18n (pt/en/es), AddonStore (protocolo Stremio), Streams (fontes e legendas),
          Net, Store (persistência), Models, Util
  ui/     App e AppState (pilha de telas), Theme (cores, Poppins, escala), Common (poster, pílulas, chips, foco)
          HomeScreen, SearchScreen, DetailsScreen, PlayerScreen, AddonsScreen, SettingsScreen, DialogView
app/src/main/res/     fonte Poppins, ícones do player, banner da TV
.github/workflows/    build-apk.yml (gera o APK e publica as Releases)
```

## Versões

- **1.3.0** – Player próprio com barra de tempo, ficha de abertura, legendas e áudio, próximo episódio automático;
  destaques na tela inicial e linhas por gênero; detalhes dos addons; novos ajustes; sinopse buscada sob demanda;
  chave de assinatura fixa.
- **1.2.x** – Primeira versão para Android TV, com a interface do Kinora Roku 1.2. Correção dos filtros que voltavam
  para a aba Início.

## Avisos

O Kinora não inclui, hospeda nem indexa nenhum vídeo. As fontes vêm de addons que o próprio usuário adiciona,
e o usuário é responsável por assistir apenas conteúdo que tenha direito de ver. O projeto não recomenda nem
divulga nenhum addon. "Android TV" e "Google TV" são marcas do Google LLC; "Stremio" e "Cinemeta" pertencem aos
seus donos. O vídeo de teste é *Big Buck Bunny* (c) Blender Foundation, CC BY 3.0.

Código sob licença MIT (`LICENSE`); fonte Poppins sob SIL OFL 1.1 (`OFL-Poppins.txt`).
