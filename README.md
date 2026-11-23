# HandsOn VR

Add-on para o [Vivecraft](https://modrinth.com/mod/vivecraft) (Minecraft 26.3, Fabric) que deixa o inventário com cara de jogo nativo de VR. O jogador mexe nos itens com as mãos, sem telas que o deixam parado.

- **Coldres físicos:** ombro direito e os dois quadris guardam qualquer item. Leve a mão ao coldre e segure o grip para sacar o item; solte o grip para guardá-lo de volta. Para guardar o item da mão, feche a mão nele (grip), leve-o ao coldre e solte.
- **Mochila no ombro esquerdo:** grip no ombro abre a mochila na mão, no estilo de *Saints & Sinners*. Ela tem 3 páginas de 3x3, crafting 2x2, armadura e uma zona de descarte. A outra mão pega, move, junta e descarta itens. Para guardar o item da mão, solte o grip **por cima do ombro esquerdo**, ou num espaço da mochila aberta.
- **Menu radial da hotbar:** segure B (mão direita) ou X (mão esquerda). A hotbar aparece em círculo, com a mão vazia no centro; leve a mão até um item e solte o botão.
- **Coleta na mochila:** o que você coleta do chão vai primeiro para o inventário principal (a mochila). A hotbar só recebe quando ele enche, então ela fica com o que você escolheu.
- **Arco na mão que o pegou:** pegue o arco com uma mão e a flecha aparece na outra. Encoste a flecha na corda, segure o gatilho, puxe e solte. Opcional: a mira do jogo mostrando onde a flecha vai cair (desligada por padrão).
- **Mesa de trabalho imersiva:** com a mochila numa mão, ponha os itens na grade 3x3 em cima da mesa com a outra. Eles ficam deitados nas casas, o resultado flutua acima, o grip nele fabrica uma vez e grip + gatilho fabrica tudo, direto na sua mão.
- **Fornalhas imersivas:** olhe para uma fornalha, um alto-forno ou um defumador e ponha os itens com a mão. Na fornalha, o que vai queimar fica na abertura de cima e o combustível na de baixo; no alto-forno e no defumador, o que vai cozinhar fica em cima do bloco. Acima dele aparece a barra de progresso da tela do jogo (a chama e a seta), e o que fica pronto flutua ao lado, para pegar com o grip. As fornalhas a até 10 blocos mostram o que têm.
- **Arremesso com a mão:** segure uma bola de neve, um ovo, uma pérola do Ender, uma poção arremessável ou uma carga de vento com o grip, faça o movimento e solte. O item sai na direção da mão. Nos servidores, a velocidade é a do jogo; no singleplayer, a força do gesto conta.
- **Pulso e mão:** vire o pulso esquerdo para o rosto e veja a vida, a armadura, a fome, a XP e os efeitos; a quantidade da pilha aparece perto do dedão.
- **Minimapa no pulso direito:** com o Xaero's Minimap, levante a mão direita com a palma para o rosto e o mapa está na parte de dentro do pulso.
- **Fogueira, armadura, baú e poções:** encoste a comida crua num dos 4 cantos da fogueira; leve o capacete à cabeça e o peitoral ao peito para vestir; segure a tampa do baú e levante a mão para abrir, e os itens aparecem deitados no fundo escuro da caixa (9x3, ou 9x6 no baú duplo); encoste a garrafa, o ingrediente ou o pó de blaze no lugar dele no suporte de poções.
- **Multiplayer:** no mundo aberto pelo jogo (LAN ou Essential), quem tem o HandsOn vê os itens nos coldres e a mochila dos amigos em VR.
- **Regra das mãos:**
  - O que você pega com o grip é emprestado: ao soltar, volta para o lugar.
  - O que você escolhe no radial fica na mão.
  - Isso funciona até com o inventário 100% cheio.

![Mochila aberta](docs/img/mochila.png)

O design completo está em [docs/DESIGN.md](docs/DESIGN.md). O padrão visual (tudo com a cara do Minecraft) está em [docs/VISUAL.md](docs/VISUAL.md). O histórico de versões está no [CHANGELOG](CHANGELOG.md).

## Controles (Quest 2)

| Botão | Ação |
|---|---|
| Grip | Pegar (coldre, mochila, item da mochila ou do radial) enquanto estiver apertado. Em qualquer outro lugar, segura o item da própria mão. |
| Soltar o grip | Devolve o item ao lugar dele. Sobre um espaço da mochila, coloca ali; num coldre, guarda ali; no ombro esquerdo, guarda na mochila. |
| Gatilho com o grip apertado | Coloca um item por vez num espaço ou na zona de descarte. |
| Gatilho segurado passando por vários espaços | Divide a pilha igualmente entre eles (mesa e mochila), como arrastar na tela do jogo. |
| Gatilho + grip num espaço | Pega a metade. |
| Grip no resultado do crafting | Fabrica uma vez (mochila ou mesa) e o resultado vai para a mão. |
| Grip + gatilho no resultado do crafting | Fabrica tudo o que der, direto na mão. |
| Grip no resultado de uma fornalha | Leva tudo o que está pronto para a mão. |
| Soltar o grip no meio de um arremesso | Joga um item arremessável na direção do movimento. |
| B / X | Menu radial da hotbar da mão direita / esquerda. |
| Gatilho esquerdo | Usa o item da mão esquerda. |
| Analógico direito | Gira a câmera, como no Vivecraft. A hotbar fica com o menu radial (B/X). |

Tudo pode ser ligado e desligado em **Opções HandsOn VR**, no menu de pausa (separadas em grupos, com rolagem).

## Requisitos

| Componente | Versão |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 ou superior |
| Fabric API | 0.166.2+26.3 |
| Vivecraft | 1.3.18 (Fabric, 26.3) |
| Java | 25 |

O mod só roda no cliente. Ele usa os cliques normais do inventário do Minecraft, então funciona em qualquer servidor.

## Compilar

O build usa o jar do Vivecraft, que não faz parte do repositório. Baixe o `vivecraft-26.3-1.3.18-fabric.jar` no Modrinth (ou copie da pasta `mods` do perfil) e coloque-o em `../libs/`, ou seja, numa pasta `libs/` ao lado da pasta do projeto. O nome precisa ser exatamente esse.

O Minecraft 26 exige Java 25, e o Loom precisa do Gradle rodando em Java 25. Se ele não estiver instalado, o Gradle baixa um para o próprio cache (`gradle/gradle-daemon-jvm.properties`), sem instalar nada no sistema. A 0.1.0 (Minecraft 1.21.1) continua no histórico do git.

```bash
./gradlew build
```

O jar sai em `build/libs/`.

## Testes automáticos (sem óculos)

```bash
./gradlew prodClient          # VR simulado: coldres, mochila, radial, inventário cheio, prints
./gradlew prodClientNullVr    # VR nulo do Vivecraft: ligação com grips, gatilhos, A, B e X
```

Os relatórios ficam em `run-prod/handsonvr-selftest.txt` e `run-prod-nullvr/handsonvr-selftest.txt`. Os prints ficam em `run-prod/screenshots/`.

Para testar dentro do modpack de verdade (Sodium, Iris, Vivecraft e o resto do Fabulously Optimized), aponte para a pasta `mods` do perfil. O HandsOn do perfil e o Essential ficam de fora:

```bash
./gradlew prodClient "-Pmodpack=C:/Users/<você>/AppData/Roaming/ModrinthApp/profiles/Fabulously Optimized/mods"
```

Com o Minecraft 26.3 "puro" (sem o modpack), o jogo pode fechar sozinho ao carregar os recursos em placas NVIDIA, por causa da "otimização de threads" do driver. O Sodium do modpack contorna isso. Se acontecer, rode de novo ou use `-Pmodpack`.

## Licença

MIT. Veja [LICENSE](LICENSE).

## Sobre o desenvolvimento

Este projeto foi feito com ajuda de IA (Claude, da Anthropic). O código foi revisado e testado pelo autor.

## Relação com o CobbleVR

Este mod funciona sozinho. Ele também roda junto com o [CobbleVR](../README.md) para o mesmo jogo.
