# Changelog

Todas as versões do HandsOn VR. As issues e os pull requests estão no [GitHub](https://github.com/DemianGomes/handsonvr). Cada mudança testada ganha uma versão nova (0.2.1, 0.2.2...).

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
