# HandsOn VR

Add-on para o [Vivecraft](https://modrinth.com/mod/vivecraft) (Minecraft 1.21.1, Fabric) com **coldres físicos** e controles extras para o Quest 2.

- **Coldres:** itens presos nos ombros e nos quadris. Leve a mão ao coldre e segure o grip para pegar o item. Solte o grip para guardá-lo.
- **Controles:** o gatilho esquerdo usa a mão esquerda; o analógico direito troca a barra de atalhos.

## Requisitos

| Componente | Versão |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | 0.16.0 ou superior |
| Fabric API | 0.116.x+1.21.1 |
| Vivecraft | 1.3.15 (Fabric) |
| Java | 21 |

## Compilar

O build usa o jar do Vivecraft, que não faz parte do repositório. Baixe o `vivecraft-1.21.1-1.3.15-fabric.jar` no Modrinth e coloque-o em `../libs/`, ou seja, numa pasta `libs/` ao lado da pasta do projeto. O nome precisa ser exatamente esse.

```bash
./gradlew build
```

O jar sai em `build/libs/`.

## Testes automáticos

```bash
./gradlew prodClient          # com VR simulado
./gradlew prodClientNullVr    # VR nulo (sem óculos)
```

## Licença

MIT. Veja [LICENSE](LICENSE).

## Sobre o desenvolvimento

Este projeto foi feito com ajuda de IA (Claude, da Anthropic). O código foi revisado e testado pelo autor.

## Relação com o CobbleVR

Este mod funciona sozinho. Ele também roda junto com o [CobbleVR](../README.md) para o mesmo jogo.
