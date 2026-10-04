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

> **Atualizando da versão 1.2.x:** desinstale o app antigo antes (a chave de assinatura mudou).
> A partir da 1.3.0, as versões novas instalam por cima, sem desinstalar.

### Atualização pelo próprio app
Ao abrir, o Kinora consulta as releases do GitHub e avisa uma vez quando há versão nova, com o botão
**Baixar e instalar**: o app baixa o APK e abre o instalador do Android (você confirma na tela). Também dá para
verificar em **Ajustes > Sobre e ajuda > Verificar atualização**. Na primeira vez, o Android pede para permitir que o
Kinora instale apps (fontes desconhecidas).

## O que o app faz

- **Perfis ("Quem está assistindo?")**: até 6 perfis, cada um com histórico, Minha lista, episódios assistidos,
  pesquisas recentes, legenda/áudio lembrados e ajustes próprios (addons e PIN são compartilhados). **Perfil infantil**:
  conteúdo adulto sempre oculto e Ajustes/Addons bloqueados. O perfil ativo aparece no fim da barra do topo.
- **Início, Filmes e Séries** com linhas de catálogo, "Continuar assistindo" com barra de progresso, **Minha lista** e
  linhas de filmes por gênero. Catálogos guardados em memória por 5 minutos.
- **Banner de destaques**: gira sozinho com fade, **luz ambiente** colorida que muda com cada título, **zoom lento**
  opcional, selos (tipo, IMDb) e os botões **Detalhes** e **Assistir/Continuar** (abre a ficha e já toca, preferindo a
  mesma fonte de antes). O que você estava assistindo vai primeiro.
- **Filtros** de catálogo e de gênero/ano em Filmes e Séries.
- **Busca** com teclado próprio e **pesquisas recentes**.
- **Ficha do título**: sinopse, elenco, direção, duração, IMDb, classificação indicativa (quando o addon fornece),
  botões Assistir e **Minha lista**, temporadas e episódios com barra de assistido/em andamento.
- **Fontes** dos addons, ordenadas pela **qualidade preferida**, com um vídeo de teste no fim da lista.
- **Fontes P2P (infoHash)**: fontes de addons que só trazem `infoHash` (torrent) tocam pelo motor P2P embutido
  (veja abaixo). Aparecem com a marca **P2P**, depois das fontes diretas.
- **Player no estilo Netflix/YouTube**: Esquerda/Direita pulam na hora mostrando só a barra vermelha; OK pausa
  mostrando só a barra; Baixo abre os controles completos; **troca automática de fonte** se o vídeo falhar ou demorar
  mais de 30 s; legenda e áudio **lembrados por título**; ficha de abertura; próximo episódio automático.
- **Addons** em cartões com ícone e status, painel de detalhes e botões **Ativar/Desativar, Atualizar, Configurar e
  Remover**; no topo, **Adicionar addon** e **Atualizar todos**.
- **Ajustes em 7 categorias**: Geral, Reprodução, Legendas e áudio, Segurança e conteúdo (ocultar adulto, **PIN**),
  Perfis, Dados (limpar histórico, lista, pesquisas, assistidos; restaurar addons e ajustes) e Sobre e ajuda
  (atualização, **diagnóstico e autoteste**, atalhos do controle).

## Fontes P2P (infoHash)

Alguns addons devolvem fontes sem endereço HTTP, só com `infoHash` (e às vezes `fileIdx` e trackers em `sources`).
O Kinora toca essas fontes com um motor P2P embutido ([libtorrent4j](https://github.com/aldenml/libtorrent4j)):

1. busca os metadados pelos trackers do addon e pelo DHT ("Conectando via P2P... N pares");
2. escolhe o arquivo indicado pelo addon (ou o maior vídeo) e baixa só ele, em ordem;
3. com o começo e o fim do arquivo baixados, o player abre um endereço local (`127.0.0.1`) e o vídeo continua
   chegando enquanto você assiste; pular para a frente dá prioridade às partes daquele ponto.

Os dados ficam no cache do app e são **apagados ao fechar o player**. É preciso ter espaço livre do tamanho do
arquivo. Enquanto assiste, o app também **envia** partes do vídeo para outros pares (é assim que o P2P funciona).
Dá para desligar em **Ajustes > Reprodução > Fontes P2P (torrent)**; desligado, essas fontes não aparecem.
O motor precisa de **Android 7 ou mais novo** e deixa o APK maior. Fontes com poucos pares demoram ou falham; nesse
caso o player tenta a próxima fonte da lista.

## Adicionar addon por link

O Kinora abre links `stremio://...` e `kinora://add?addon=<url>`, então dá para adicionar um addon a partir de outro
app da TV. Pelo computador (com o ADB conectado à TV):

```
sh tools/add-addon.sh https://endereco-do-addon/manifest.json
```

A TV pede confirmação antes de adicionar. Com um PIN ativo, a adição por link fica bloqueada.

## Controle remoto

| Onde | Tecla | O que faz |
|---|---|---|
| Telas | Setas / OK / Voltar | Navegar, abrir, voltar |
| Tela inicial | Play | Abre e toca o destaque do banner |
| Busca | Retroceder / Avançar | Apagar / espaço |
| Busca | Play | Ir para os resultados |
| Perfis | Menu ou segurar OK | Menu do perfil (entrar, renomear, infantil, excluir) |
| Addons | Menu ou segurar OK | Remover o addon |
| Player | Esquerda / Direita | Pula na hora (10, 15 ou 30 s; acelera se repetir) |
| Player | OK | Pausar / continuar (com a barra na tela) |
| Player | Baixo | Controles completos |
| Player (controles) | Cima | Ir para um ponto da barra (confirma com OK) |
| Player | Play, Retroceder, Avançar | Pausar, -30 s, +30 s |
| Player | Voltar | Fecha os controles ou sai |

## Diferenças em relação ao Roku

- Fontes P2P (infoHash) tocam no app (no Roku não são suportadas).
- O player usa o **Media3 / ExoPlayer**: toca MP4, MKV, HLS (`.m3u8`) e DASH (`.mpd`), com os cabeçalhos HTTP
  do addon (`behaviorHints.proxyHeaders`). Legendas externas em SRT, VTT ou SSA.
- O `*` do controle Roku virou **Menu** ou **segurar OK** (Addons e Perfis).
- O PIN é digitado num teclado numérico do próprio app (as teclas numéricas do controle também funcionam).
- A atualização é feita pelo próprio app (no Roku é um script no computador), e o "adicionar addon pela rede" do Roku
  virou o link `kinora://add` (veja acima).
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
   git tag v1.5.4
   git push origin v1.5.4
   ```
3. O Actions cria a Release `v1.5.4` com o `Kinora-AndroidTV.apk` anexado.

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
          Net (e log de erros), Store (persistência por perfil), SelfTest (autoteste), Models, Util,
          TorrentEngine (motor P2P + servidor HTTP local)
  ui/     App e AppState (pilha de telas), Theme (cores, Poppins, escala), Common (poster, pílulas, chips, foco)
          HomeScreen, SearchScreen, DetailsScreen, PlayerScreen, AddonsScreen, SettingsScreen,
          ProfileScreen, DiagScreen, DialogView (diálogos e PIN), Updater (atualização pelo app)
app/src/main/res/     fonte Poppins, ícones do player, banner da TV
.github/workflows/    build-apk.yml (gera o APK e publica as Releases)
tools/                add-addon.sh (adicionar addon pela TV via ADB)
```

## Versões

- **1.5.3** – Fontes P2P (infoHash) com motor embutido (libtorrent4j) e servidor local com Range, ajuste para
  ligar/desligar, estado de conexão e velocidade no player, limpeza automática dos dados.
- **1.5.2** – Igual ao Kinora Roku 1.5.2: perfis (com infantil e PIN), luz ambiente, zoom e destaques com
  Assistir/Continuar, Minha lista, episódios assistidos, pesquisas recentes, elenco/direção/duração, player no estilo
  Netflix/YouTube com troca automática de fonte e qualidade preferida, legenda/áudio lembrados por título, addons em
  cartões com atualizar/configurar, ajustes em categorias, diagnóstico e autoteste, aviso e atualização pelo app.
- **1.3.0** – Player próprio com barra de tempo, ficha de abertura, legendas e áudio, próximo episódio automático;
  destaques na tela inicial e linhas por gênero; detalhes dos addons; novos ajustes; sinopse buscada sob demanda;
  chave de assinatura fixa.
- **1.2.x** – Primeira versão para Android TV, com a interface do Kinora Roku 1.2. Correção dos filtros que voltavam
  para a aba Início.

## Avisos

O Kinora não inclui, hospeda nem indexa nenhum vídeo. As fontes vêm de addons que o próprio usuário adiciona,
e o usuário é responsável por assistir (e, no caso das fontes P2P, compartilhar) apenas conteúdo que tenha direito
de usar. O motor P2P é a libtorrent4j (MIT). O projeto não recomenda nem
divulga nenhum addon. "Android TV" e "Google TV" são marcas do Google LLC; "Stremio" e "Cinemeta" pertencem aos
seus donos. O vídeo de teste é *Big Buck Bunny* (c) Blender Foundation, CC BY 3.0.

Código sob licença MIT (`LICENSE`); fonte Poppins sob SIL OFL 1.1 (`OFL-Poppins.txt`).
