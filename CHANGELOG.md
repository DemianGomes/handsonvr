# Changelog

Todas as versões do HandsOn VR. As issues e os pull requests estão no [GitHub](https://github.com/DemianGomes/handsonvr). Cada mudança testada ganha uma versão nova (0.2.1, 0.2.2...).

## 0.10.0-alpha (Minecraft 26.3)

### Mineração em grupo, uma cópia temporária do FTB Ultimine ([#63](https://github.com/DemianGomes/handsonvr/issues/63))

- O FTB Ultimine não roda no 26.3 (nenhuma versão lançada é do 26.3, e a do 26.1.2 usa 17 partes do jogo que mudaram). Até a FTB lançar, o HandsOn tem as mesmas formas: **sem forma, túnel pequeno, quadrado pequeno, túnel grande, túnel de mineração e túnel de fuga** (até 64 blocos).
- Os botões abaixo do relógio do pulso esquerdo ligam (a picareta) e trocam a forma (as setas); o nome da forma fica embaixo deles. Ligada, os blocos que vão quebrar ficam com o contorno do jogo.
- A quebra é a do jogo, bloco a bloco: itens, ferramenta e fome como sempre; para antes de a ferramenta quebrar. Precisa do HandsOn no servidor (singleplayer, LAN, Essential).
- Quando o FTB Ultimine do 26.3 sair e for instalado, a cópia se desliga e os botões passam a controlar ele.

## 0.9.0-alpha (Minecraft 26.3)

- **Minimapa no pulso direito** ([#60](https://github.com/DemianGomes/handsonvr/issues/60)): o minimapa do Xaero's Minimap sai do HUD do Vivecraft (pulso esquerdo) e fica grudado na parte de dentro do pulso direito. Levante a mão direita com a palma virada para o rosto: o mapa fica de pé, com o "cima" para os dedos. O chat e o resto do HUD continuam no pulso esquerdo, sem o mapa por cima. Opção "Minimapa no pulso direito".
- **FTB Ultimine em VR** ([#61](https://github.com/DemianGomes/handsonvr/issues/61)): com o Ultimine instalado, três botões abaixo do relógio do pulso esquerdo: a picareta liga e desliga o Ultimine (como segurar a tecla), as setas trocam a forma. Aperte encostando a ponta da mão direita. **O FTB Ultimine ainda não tem versão para o 26.3**: a mais nova (26.1.2.7, com a FTB Library 26.1.2.12) fecha o jogo do 26.3 ao abrir (a FTB Library usa uma parte do controle de teclas que o 26.3 tirou). Testado numa cópia dos mods, fora do perfil. Os botões ficam prontos para quando sair a versão do 26.3 (e ainda não foram testados com ele).

## 0.8.6-alpha (Minecraft 26.3)

- **HUD do Vivecraft só com o que importa, sempre no pulso esquerdo.** Em VR, o HUD do Vivecraft fica à vista e preso ao pulso esquerdo, com só: o chat, a lista de jogadores, os títulos, as mensagens da barra de ação, a barra do chefe, o placar do servidor e o HUD de outros mods, como o **minimapa do Xaero's Minimap** (aparece sempre que o mod está instalado; sem ele, nada muda). Saem dele a hotbar, a vida, a armadura, a fome, as bolhas, a XP, os efeitos, o nome do item, a mira do jogo e as sobreposições de tela cheia (o relógio do pulso e o radial já mostram isso).
- Se o HUD estava escondido (o F1, ou o "esconder HUD" do Vivecraft), o HandsOn mostra ele ao ligar o VR e devolve como estava ao desligar. Tudo em memória: nenhum arquivo do Vivecraft ou do jogo é alterado. Sem VR, o HUD do jogo é o de sempre.
- A opção "Barras só no pulso" saiu: em VR o HUD é sempre assim.

## 0.8.5-alpha (Minecraft 26.3)

- **Dica do item na mochila e no baú VR:** com a mão sobre um item aparece a caixa de dica do jogo, com o nome, os encantamentos, os efeitos e a descrição (como no inventário do jogo).
- **Baú aberto e outros blocos ao mesmo tempo:** o servidor só deixa um menu aberto por vez. Agora, ao levar a mão à fornalha (ou ao suporte de poções, ou à mesa), o baú cede o menu e fica **suspenso**: continua à vista com o que tinha e reabre sozinho quando a mão volta aos espaços dele. A tampa pode fechar e abrir rapidinho nessa troca.
- **Bolhas de ar no relógio do pulso** quando você está debaixo d'água (ou o ar ainda não voltou).
- **HUD do Vivecraft com o minimapa:** em VR, em vez de esconder o HUD inteiro (o F1), o HandsOn tira dele só a hotbar, a vida, a armadura, a fome, as bolhas, a XP, os efeitos e o nome do item (o relógio do pulso já mostra). O resto continua: o minimapa do Xaero e o HUD de outros mods, o chat. A opção agora se chama "Barras só no pulso".
- **Botão "Organizar" (A-Z)** na mochila (à direita da primeira fileira) e no baú VR (abaixo da grade): junta as pilhas iguais e põe em ordem, com cliques normais. É do próprio HandsOn: funciona com ou sem o Inventory Profiles Next.
- **Compatível com o Inventory Profiles Next:** o reabastecimento automático do IPN via a mão esvaziar ao guardar um item no coldre ou na mochila e punha o item de volta na mão. Agora, depois de cada ação do HandsOn, o IPN recomeça a "foto" da mão; ele continua repondo o que acaba de verdade (um bloco colocado, uma ferramenta quebrada). Os cliques do HandsOn também não passam mais pelo ponto que outros mods interceptam. Testado com o IPN, o Xaero's Minimap e o Voxy carregados.

## 0.8.4-alpha (Minecraft 26.3)

### Depois do teste em multiplayer (dois jogadores em VR)

- **Baú (de novo, a causa certa):** com a mão dentro de um bloco, o Vivecraft liga a ação "VR Interact" e o grip vai para ela, não para a ação que o HandsOn escutava. Na 0.8.3 o módulo do HandsOn segurava essa ação mas não fazia nada, então com a mão no baú o grip não abria a tampa nem pegava itens. Agora o módulo **é** o grip do HandsOn ali (apertar, segurar e soltar): a tampa abre com o movimento e o grip pega e põe itens com a mão dentro do baú. Vale também para o suporte de poções, as fornalhas e a mesa.
- **Mira do arco ligada por padrão.** Quem já tinha a configuração recebe a mira ligada uma vez (dá para desligar nas opções).
- **Coldres:** sacar um item do coldre (ombro ou cintura) e guardá-lo na mochila (por cima do ombro ou num espaço da mochila aberta) esvazia o coldre.
- **Quantidade na mão:** agora fica grudada na lateral do dedão, como o relógio do pulso, e não some mais dentro da mão.
- **HUD do Vivecraft escondido por padrão em VR** (a hotbar e a vida no pulso de fora): é o mesmo "esconder HUD" do F1, que estava ligado só em um dos jogadores. Sem VR o HUD volta. Opção "Sem HUD do Vivecraft".
- **Menu radial: espaço de descarte** abaixo do círculo, como o da mochila. Soltar o grip ali com um item joga a pilha fora; o gatilho joga um; soltar o B sobre ele joga fora a pilha da mão.
- **Suporte de poções agachado:** já é o padrão do mod desde a 0.8.1 (não é configuração). O teste agora confere com o suporte fechado e o jogador agachado.
- **Opções HandsOn VR** (antes "Coldres VR"; alguns nomes ficaram mais curtos para caber: "Relógio no pulso", "Coleta na mochila", "Sem HUD do Vivecraft"): as opções ficam em grupos (Coldres; Mochila e menu radial; Mãos e itens; HUD) numa lista que rola com a roda do mouse ou o analógico, com os botões ▲ ▼ ao lado.

## 0.8.3-alpha (Minecraft 26.3)

- **Baú: o grip não abre mais a janela antiga.** A causa era o "VR Interact" do Vivecraft: com a mão dentro de um bloco, o grip faz o "usar" do jogo nele. No baú fechado isso abria a janela antiga; no baú aberto, o grip para pegar um item reabria a janela antiga e fechava o baú novo. Agora o HandsOn registra no Vivecraft um módulo de interação que vem antes do dele e deixa o grip só para o HandsOn quando a mão está num baú, barril, baú do Ender, caixa de shulker, suporte de poções, fornalha ou mesa de trabalho.
- **Baú: o A (usar) abre o baú novo.** Em VR, apertar A olhando para um baú, barril ou caixa de shulker abre o baú imersivo (os itens no fundo da caixa), e não mais a janela antiga.
- Sem VR (ou com o VR desligado) nada muda: o baú é o do jogo.

## 0.8.2-alpha (Minecraft 26.3)

### Correções da 0.8.1

- **Baú:** tirei o "abrir pelo usar normal vira o baú imersivo" da 0.8.1: ele mexia com o jeito normal de abrir o baú e atrapalhava o grip nos itens. O baú aberto de forma normal volta a ser exatamente o do jogo (e sem VR nada muda). O gesto da tampa (VR) segue igual.
- **Baú (VR):** a folga para a mão tocar um espaço do fundo do baú ficou maior (10 cm abaixo, 20 cm acima), com o grip num espaço pegando o item. Grip de **mão vazia** no fundo e empurrar para baixo fecha o baú. Num baú duplo, a outra metade não reabre mais o baú.
- **Pulso:** o coração cheio ficava no mesmo plano do recipiente e piscava em vermelho e preto; agora fica um pouco à frente.

## 0.8.1-alpha (Minecraft 26.3)

### Ajustes depois do teste no óculos

- **Abaixar o corpo não atrapalha mais os blocos baixos.** Abaixar de verdade conta como Shift, e o jogo ignora o bloco com um item na mão agachado. Agora o mod tira o Shift só durante o "usar" dele (suporte de poções, fogueira, baú, fornalha, mesa) e devolve em seguida; seu corpo continua abaixado.
- **Fogueira: os 4 cantos.** A comida entra encostando a mão num dos 4 cantos livres (um espaço aparece em cada um); o meio do fogo não conta mais. O jogo enche sempre o primeiro canto livre, na ordem dele.
- **Baú: os itens ficam dentro da caixa**, deitados no fundo escuro do baú aberto, e não mais na tampa.
- **Baú: a quantidade** (areia, pedra e outros blocos) não fica mais atrás do item.
- **Pulso:** o relógio fica mais afastado do braço, fora da segunda camada (mangas) das skins, e **continua aparecendo com a mochila na mão**.
- **Suporte de poções: o pó de blaze** tem um lugar só dele, ao lado do ingrediente (à esquerda de quem olha, como na tela do jogo), flutuando e longe dos outros; o espaço mostra o item da mão.

## 0.8.0-alpha (Minecraft 26.3)

### Baú: abrir a tampa com o movimento ([#51](https://github.com/DemianGomes/handsonvr/issues/51))

- Segure a borda da frente da tampa com o grip e **levante a mão** para abrir. Grip no baú aberto e **empurre para baixo** para fechar. No barril e na caixa de shulker, puxe a face da tampa para fora.
- Os itens aparecem **na parte de dentro da tampa aberta**, sem página nem rolagem: 9x3 no baú simples e **9x6 no baú duplo**, que usa a tampa das duas metades. No barril e na caixa de shulker, deitados na face da tampa.

### Fogueira e suporte de poções: encostar ([#52](https://github.com/DemianGomes/handsonvr/issues/52))

- Com o item na mão, **encoste a mão** na fogueira ou no lugar do suporte de poções (sem apertar nada) e um item vai para lá. Para pôr outro, tire a mão e encoste de novo.
- Espaços do inventário do jogo mostram onde pôr: sobre a fogueira com comida crua na mão, e em cada lugar vazio do suporte que aceita o item. Clareiam com a mão no lugar.
- Os lugares do suporte ficaram maiores (17 cm de alcance) e a fogueira aceita a mão desde dentro dela até 85 cm acima, com folga dos lados.

### Arremessáveis na palma ([#53](https://github.com/DemianGomes/handsonvr/issues/53))

- Bola de neve, ovo, pérola do Ender, poções arremessáveis, frasco de experiência e carga de vento agora ficam na palma da mão, como a comida, em vez de flutuar à frente do controle (o jeito do Vivecraft).

## 0.7.1-alpha (Minecraft 26.3)

### Pulso: o relógio grudado no antebraço ([#50](https://github.com/DemianGomes/handsonvr/issues/50))

- O relógio do pulso agora fica **grudado na lateral interna do antebraço esquerdo**, como o HUD "no pulso" do Vivecraft: anda e gira com o controle, deitado ao longo do braço. Antes ele flutuava virado para o rosto e só aparecia quando você olhava.
- Para ler, vire o lado interno do braço para o rosto. Ele some enquanto a mão esquerda segura a mochila.

## 0.7.0-alpha (Minecraft 26.3)

- **Pulso esquerdo** ([#42](https://github.com/DemianGomes/handsonvr/issues/42)): virando o pulso para o rosto aparecem a vida, a armadura, a fome, a XP com o nível e os efeitos, com os ícones do HUD do jogo.
- **Quantidade na mão** ([#43](https://github.com/DemianGomes/handsonvr/issues/43)): perto do dedão, a quantidade da pilha de cada mão (só para você).
- **Fogueira** ([#44](https://github.com/DemianGomes/handsonvr/issues/44)): soltar a comida crua em cima dela põe um item; o gatilho também.
- **Vestir armadura pelo corpo** ([#45](https://github.com/DemianGomes/handsonvr/issues/45)): capacete na cabeça, peitoral ou élitro no peito, calças na cintura, botas abaixo dos joelhos.
- **Baú, barril, baú do Ender e caixa de shulker** ([#46](https://github.com/DemianGomes/handsonvr/issues/46)): grip no bloco abre a tampa sem a tela, o conteúdo aparece numa janela acima dele, e as mãos pegam, põem a pilha, põem um e pegam metade. Grip de novo ou se afastar fecha.
- **Suporte de poções** ([#47](https://github.com/DemianGomes/handsonvr/issues/47)): abre ao olhar, as garrafas nos braços, o ingrediente no topo, o pó de blaze no meio da haste, e a plaquinha com as bolhas, a seta e a barra de combustível da tela do jogo.
- **Multiplayer** ([#48](https://github.com/DemianGomes/handsonvr/issues/48)): no mundo aberto pelo jogo (LAN, Essential), os jogadores com o HandsOn veem os itens nos coldres e a mochila (uma bolsa) dos amigos em VR. Quem joga sem VR vê o jogo como sempre, mais isso.

## 0.6.0-alpha (Minecraft 26.3)

### Distribuir arrastando e pegar metade ([#39](https://github.com/DemianGomes/handsonvr/issues/39))

- **Distribuir:** segurando uma pilha com o grip, aperte o gatilho num espaço e, **sem soltar o gatilho**, passe a mão por outros. Cada um marcado clareia e o controle vibra.
  - Ao soltar o gatilho, a pilha se **divide igualmente** entre eles, como arrastar na tela do jogo: 9 canas pela linha de cima da mesa viram 3 em cada casa. O que sobrar fica na mão.
  - Soltar o gatilho no mesmo espaço em que apertou continua pondo um item só.
  - Vale na mesa de trabalho e na mochila (crafting 2x2 e inventário).
- **Pegar metade:** com o **gatilho já apertado**, o grip num espaço com itens traz a metade para a mão (arredondada para cima, como o clique direito). Vale na mesa e na mochila.
- O gatilho numa casa da mesa, com a mão vazia, não acerta mais a mesa (não começa a quebrá-la).
- Por baixo, são os cliques normais do jogo (o arrastar e o clique direito): funciona em qualquer servidor.

## 0.5.1-alpha (Minecraft 26.3)

### Fornalhas: o conteúdo aparece de longe ([#38](https://github.com/DemianGomes/handsonvr/issues/38))

- Todas as fornalhas, alto-fornos e defumadores a até **10 blocos** mostram o que têm: os itens, a barra de progresso e o resultado flutuando. Antes, só a que você olhava bem de perto.
- **No singleplayer**, tudo ao vivo: o mod lê as fornalhas no servidor do próprio jogo a cada quarto de segundo.
- **Em servidor**, a fornalha aberta (a que você olha de perto) aparece ao vivo. As outras mostram o que tinham na última vez que você as viu abertas, porque o servidor só manda o conteúdo de uma fornalha aberta.
- Usar com as mãos continua igual, de perto.

## 0.5.0-alpha (Minecraft 26.3)

### Arremesso com a mão ([#36](https://github.com/DemianGomes/handsonvr/issues/36))

- Bola de neve, ovo, pérola do Ender, poção arremessável e persistente, frasco de experiência e carga de vento podem ser jogados com o movimento do braço: segure com o grip, faça o movimento e **solte o grip**. Sai um item, na direção em que a mão estava indo.
- Funciona com o item da mão ou pego de um coldre, da mochila ou do radial. O resto da pilha volta para onde estava.
- Um movimento devagar não arremessa. O gatilho continua arremessando do jeito do Vivecraft.
- A direção é medida no quarto: andar ou girar não entortam o arremesso. As poções e o frasco de experiência saem retos (o mod desconta os 20° para cima do jogo).
- **Nos servidores**, a velocidade e a gravidade são as do jogo: o gesto escolhe só a direção, como mirar com o mouse. Nada muda no servidor e ele não precisa do mod.
- **Força real, só no singleplayer** (ligada por padrão): a força do gesto muda a velocidade. Fraco cai perto; forte vai um pouco além.
- Duas opções novas nos ajustes: "Arremesso com a mão" e "Força real (singleplayer)".
- O design ganhou uma seção sobre servidores: o que o mod manda ao servidor e o que pode gerar alerta de anticheat.

## 0.4.0-alpha (Minecraft 26.3)

### Fornalhas imersivas: fornalha, alto-forno e defumador ([#34](https://github.com/DemianGomes/handsonvr/issues/34))

- Olhe para uma fornalha perto: ela abre por baixo, sem a tela, e mostra o que tem dentro. Leve um item até ela com o grip.
  - **Soltar o grip** num lugar põe a pilha inteira.
  - **Grip + gatilho** põe um item por vez.
  - **Grip** num lugar com item o traz de volta para a mão.
- **Fornalha:** o que vai queimar fica em pé na abertura de cima da frente; o combustível, na abertura de baixo, onde aparece o fogo.
- **Alto-forno:** carregado por cima, como um de verdade. O minério fica deitado no topo e o combustível vai na grade de fogo da frente.
- **Defumador:** a comida fica deitada em cima da chaminé, como numa grelha. O combustível vai no fogo da frente.
- **Barra de progresso:** acima do bloco, virada para você, aparecem a chama (quanto ainda queima o combustível) e a seta (quanto o item atual já cozinhou), recortadas da própria tela da fornalha do jogo.
- **Resultado:** o que fica pronto flutua à direita da seta, com a quantidade. O grip nele leva tudo para a mão, com a experiência do jogo.
- A entrada aceita só o que cozinha naquele bloco e o combustível só o que queima. O resto vibra "negado".
- A fornalha fecha ao olhar para outro lado, ao se afastar ou ao mexer na armadura/crafting 2x2 da mochila. Os itens ficam nela, como no jogo.
- Usar a fornalha do jeito normal continua abrindo a tela do jogo.

## 0.3.4-alpha (Minecraft 26.3)

### Resultado do crafting: grip sozinho fabrica uma vez ([#32](https://github.com/DemianGomes/handsonvr/issues/32))

- **Grip** no resultado (da mesa de trabalho ou do crafting 2x2 da mochila) fabrica **uma vez** e o resultado vai para a mão que pegou: com um pack de toras na mesa, vêm 4 tábuas para a mão e sobram 63 toras.
- **Grip + gatilho** continua fabricando **tudo** o que der, como o Shift+clique.
- Com o grip ainda apertado e a mão no resultado, o gatilho fabrica o resto, juntando na mesma mão (o que não couber vai para o inventário). Com o gatilho já apertado, o grip fabrica tudo de uma vez.
- Na mão direita, o resultado se junta ao que ela já tem do mesmo item; senão, ocupa um espaço vazio da hotbar e o item que ela tinha continua na hotbar.

## 0.3.3-alpha (Minecraft 26.3)

### Mira do arco, opcional ([#28](https://github.com/DemianGomes/handsonvr/issues/28))

- Nova opção **"Mira do arco"** nos ajustes, **desligada** por padrão: acertar continua dependendo da sua habilidade.
- Ligada, enquanto a corda está puxada, a mira do jogo aparece no ponto em que a flecha vai acertar (bloco ou criatura).
- A conta é a do jogo: a flecha sai da mão que puxa, na direção do arco, com a força do arco (o tempo puxando) vezes o quanto a corda está puxada, e cai com a gravidade e o ar. Não conta a pequena imprecisão aleatória do arco.
- A mira fica sempre do mesmo tamanho na vista, a qualquer distância, branca com a sombra do jogo.

## 0.3.2-alpha (Minecraft 26.3)

### Mesa: a quantidade de cada casa e o total que será fabricado ([#27](https://github.com/DemianGomes/handsonvr/issues/27))

- Cada casa da mesa de trabalho com mais de um item mostra a quantidade como no inventário: os números do jogo, brancos com a sombra, no canto da casa mais perto de você, deitados na mesa.
- O resultado flutuante mostra o **total** que o grip + gatilho vai fabricar, não só o de uma receita: 4 toras mostram 16 tábuas. A caixa de dica com o nome também mostra o total.
- O resultado do crafting 2x2 da mochila também mostra o total.

## 0.3.1-alpha (Minecraft 26.3)

### Fabricar: a pilha inteira ao soltar, tudo para a mão com grip + gatilho ([#26](https://github.com/DemianGomes/handsonvr/issues/26))

- Na mesa de trabalho, soltar o grip sobre uma casa põe a **pilha inteira**, como no crafting da mochila. Uma casa com outro item troca de lugar com ele. O gatilho com o grip apertado continua pondo um por vez.
- O resultado (da mesa e do crafting 2x2 da mochila) só sai com **grip + gatilho**: segure o grip no resultado e aperte o gatilho. Grip sozinho ou gatilho sozinho não fabricam, porque fabricar pode gastar muito material.
- Sai **tudo o que der** (como o Shift+clique da tela do jogo: 4 toras dão 16 tábuas) e vai para a **mão que pegou o resultado**, e fica nela.
- Na mão direita, o resultado ocupa o espaço da mão, se estiver vazio, ou um espaço vazio da hotbar; o item que ela tinha continua na hotbar. A esquerda recebe o resultado no lugar do que tinha.
- Com o grip ainda apertado, soltar num coldre, na mochila ou no radial guarda o resultado ali. Soltar em outro lugar o deixa na mão.

## 0.3.0-alpha (Minecraft 26.3)

### Mesa de trabalho imersiva ([#21](https://github.com/DemianGomes/handsonvr/issues/21))

- Fabricar em cima da própria mesa de trabalho, sem a tela do jogo: com a mochila numa mão, leve os itens com a outra até a grade 3x3 desenhada no topo da mesa.
- Soltar o grip sobre uma casa põe **um** item ali, deitado na mesa; o resto volta para onde estava. Com o grip apertado, o gatilho põe um por vez e a mão continua com o resto.
- Quando a receita fecha, o resultado **flutua** acima da grade, girando. Com a mão perto, ele mostra o nome e a quantidade.
- **Grip no resultado:** fabrica um e o item vem para a mão. **Gatilho no resultado:** fabrica tudo, direto para o inventário.
- Grip numa casa ocupada traz o que está nela de volta para a mão.
- Ao se afastar (ou abrir outra tela, ou deixar a grade vazia), a mesa fecha e os itens da grade voltam para o inventário, como no jogo.
- Funciona em qualquer servidor: por baixo é a mesa do jogo, aberta sem a tela, e o servidor confere a receita. Usar a mesa do jeito normal continua abrindo a tela do jogo.
- Com a mesa aberta, a armadura e o crafting 2x2 da mochila esperam ela fechar (vibram "negado").

## 0.2.7-alpha (Minecraft 26.3)

### Arco na mão que o pegou ([#20](https://github.com/DemianGomes/handsonvr/issues/20))

- O arco fica na mão que o pegou (grip na mochila ou num coldre, B ou X no radial) e a flecha aparece na outra. Antes, pegar o arco com a mão direita trazia as flechas para ela e mandava o arco para a esquerda, que podia estar segurando a mochila.
- Pego pela mão esquerda, o arco também vai para o espaço da mão principal, para o Vivecraft usar as flechas do inventário. O item da mão direita espera no lugar do arco.
- O mod liga e desliga sozinho o "Reverse Bow" do Vivecraft enquanto o arco está na mão. Sem o arco (ou com uma tela aberta), a sua escolha volta.
- O gatilho da mão da flecha fica sempre com o Vivecraft (encaixar e puxar a flecha).

## 0.2.6-alpha (Minecraft 26.3)

### Itens coletados vão primeiro para a mochila ([#19](https://github.com/DemianGomes/handsonvr/issues/19))

- O que você coleta do chão (e o que volta de uma grade de crafting, ou um item recebido) vai primeiro para o inventário principal. A hotbar só recebe quando ele está cheio. No jogo original é o contrário.
- Um item igual a uma pilha que já existe continua se juntando a ela, onde ela estiver.
- Mundo um jogador e LAN aberta por você: feito direto no servidor integrado, sem o item passar pela hotbar.
- Servidor de outra pessoa: o item novo que cair num espaço vazio da hotbar é levado para a mochila logo em seguida.
- Nova opção nos ajustes: "Coleta: mochila primeiro" (ligada).

## 0.2.5-alpha (Minecraft 26.3)

### Analógico direito volta a girar a câmera ([#18](https://github.com/DemianGomes/handsonvr/issues/18))

- O analógico direito é de novo só do Vivecraft: gira a câmera. Antes, para os lados, ele trocava o item da hotbar.
- A hotbar fica com o menu radial (B/X).
- Saiu a opção "Analógico dir.: hotbar" dos ajustes. Uma configuração antiga com ela é ignorada.

## 0.2.4-alpha (Minecraft 26.3)

### Grip na armadura vestida traz a peça para a mão ([#15](https://github.com/DemianGomes/handsonvr/issues/15))

- Com a mochila aberta, o grip numa armadura vestida (ou num item do crafting) traz a peça para a mão, como em todo outro espaço. Antes ela ia direto para um espaço livre do inventário.
- Soltar num espaço da mochila, num espaço do radial, num coldre ou no descarte leva a peça para lá. Soltar longe de tudo a veste de novo.
- Com uma espada na mão, a mão direita usa um espaço vazio da hotbar enquanto segura a peça. Sem espaço vazio, ou com a mão esquerda ocupada, vibra e nada muda.
- Nunca perde item: soltar a peça sobre algo que não se veste (terra) é recusado com vibração.

## 0.2.3-alpha (Minecraft 26.3)

### Menu radial acompanha o jogador ([#13](https://github.com/DemianGomes/handsonvr/issues/13))

- O radial fica preso à área de jogo do Vivecraft: anda e gira junto quando você anda ou vira com o analógico. Antes ele ficava parado no mundo.
- Mexer a cabeça ou se inclinar não move o radial, para ele não fugir da mão.

### Padrão visual do Minecraft ([#14](https://github.com/DemianGomes/handsonvr/issues/14))

- Novo guia [docs/VISUAL.md](docs/VISUAL.md): toda tela ou objeto do mod segue o visual do jogo.
- Radial com a cara da hotbar: casas da hotbar, a moldura de seleção da hotbar no espaço sob a mão, o nome do item na caixa de dica do jogo, o ícone do escudo no item da mão esquerda e a barreira quando não dá para esvaziar a mão. Saíram os círculos, as cores próprias e as letras "D"/"E".
- Correção: o primeiro número de quantidade desenhado depois de entrar num mundo podia falhar (leitura da fonte dentro do cache).

## 0.2.2-alpha (Minecraft 26.3)

### Guardar o item da mão ([#8](https://github.com/DemianGomes/handsonvr/issues/8), [#9](https://github.com/DemianGomes/handsonvr/issues/9))

- O grip fora das zonas segura o item da própria mão (ele continua na mão até soltar).
- Soltar num coldre guarda o item ali. Da hotbar, ele vai para o inventário principal e a mão fica vazia.
- Soltar num coldre ocupado troca o item do coldre.
- Soltar no ombro esquerdo guarda na mochila (por cima do ombro, como no *Saints & Sinners*). Junta com pilhas iguais e não veste armaduras. Mochila cheia: vibra e o item fica na mão.
- Com a mochila aberta, grip num espaço vazio (ou no fundo) segura o item da mão, e soltar num espaço o guarda ali.
- Soltar longe de tudo não muda nada.

## 0.2.1-alpha (Minecraft 26.3)

### Mochila com o visual do inventário do Minecraft ([#7](https://github.com/DemianGomes/handsonvr/issues/7))

- Espaços quadrados com o relevo do inventário do jogo, dentro de uma janela cinza com a moldura do jogo.
- Armadura vazia com os ícones do jogo (capacete, peitoral, calças, botas), sem texto.
- Crafting com o título "Fabricação" e a seta cinza do inventário.
- Descarte como um espaço quadrado com uma seta para o chão.
- Setas de página do livro de receitas.
- Quantidade com a fonte e a sombra do jogo.
- Alça de couro em arco no painel; mochila nas costas com tampa, lingueta de couro, fivela, bolso costurado e alças.

### Testes

- O teste automático não pausa quando a janela perde o foco e deixa de fora o Crash Assistant do modpack.

## 0.2.0-alpha (Minecraft 26.3)

Inventário nativo de VR ([#6](https://github.com/DemianGomes/handsonvr/pull/6)).

- Regra das mãos: o grip pega emprestado, o radial equipa ([#1](https://github.com/DemianGomes/handsonvr/issues/1)).
- Coldres com zonas largas em elipsoide ([#2](https://github.com/DemianGomes/handsonvr/issues/2)).
- Mochila no ombro esquerdo: 3 páginas de 3x3, crafting 2x2, armadura e descarte ([#3](https://github.com/DemianGomes/handsonvr/issues/3)).
- Menu radial da hotbar no B e no X ([#4](https://github.com/DemianGomes/handsonvr/issues/4)).
- Port para o Minecraft 26.3 (Java 25, Vivecraft 1.3.18), a versão do Fabulously Optimized.

## 0.1.0-alpha (Minecraft 1.21.1)

- Coldres físicos no ombro direito e nos quadris, e controles extras para o Vivecraft.
