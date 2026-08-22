# APK Editor Plus

APK Editor Plus é um editor de APK para Android desenvolvido em Kotlin. Esta versão está sendo modernizada com Jetpack Compose e Material 3 e teve as funções visíveis comparadas com o APK Editor original decompilado para recuperar comportamentos que ainda estavam superficiais no projeto.

> [!WARNING]
> Projeto em desenvolvimento. Faça backup dos APKs antes de editar e modifique somente aplicativos para os quais você tenha autorização.

## O que foi implementado nesta versão

### Edição simples

A tela de edição simples agora utiliza um `ViewPager2` real com três fragments independentes:

- **Arquivos**: navegação hierárquica pelas pastas do APK, substituição, exportação e remoção de alterações pendentes por arquivo.
- **Imagens**: lista somente recursos de imagem encontrados em `res/drawable*` e `res/mipmap*`, mostra a miniatura verdadeira e agrupa variantes de densidade que possuem o mesmo nome.
- **Áudios**: localiza formatos de áudio comuns, permite reproduzir ou pausar, substituir e exportar cada arquivo.
- O botão **Fechar** muda para **Salvar** quando existe alguma alteração e encaminha os arquivos modificados para a reconstrução do APK.
- Ao substituir uma imagem agrupada, cada pasta de densidade recebe uma variante redimensionada para as dimensões originais; arquivos NinePatch são protegidos contra conversão incorreta.

### Miniaturas reais nos gerenciadores

- Arquivos PNG, JPG, JPEG, WebP, GIF e BMP mostram a própria imagem no lugar do ícone genérico.
- As miniaturas funcionam tanto para arquivos do armazenamento quanto para imagens compactadas dentro de APKs e ZIPs.
- APKs continuam exibindo o ícone real do aplicativo.
- A leitura é feita sob demanda, com redução de resolução e limite de 16 MB por imagem para evitar consumo excessivo de memória.
- O mesmo componente visual é utilizado no seletor de APK, editor simples, editor completo, navegador XML/AXML e seletor de assinatura.

### Strings e idiomas

- O seletor principal mostra somente os idiomas que realmente existem no APK aberto.
- A ação de adicionar idioma mostra apenas os idiomas ainda ausentes.
- Os idiomas são apresentados com nome, código e bandeira.
- Ao adicionar um idioma, os textos copiados ficam destacados em vermelho para indicar que precisam ser traduzidos antes de salvar.
- A lógica considera os diretórios `values`, `values-xx` e qualificadores regionais presentes nos recursos.

### Histórico de diferenças

- Novo fragment **Diferenças** dentro da edição completa.
- Alterações de texto são exibidas linha por linha: removidas em vermelho e adicionadas em verde.
- Arquivos binários modificados também são reconhecidos e identificados.
- Cada arquivo possui seu próprio histórico e pode ser descartado individualmente.
- Não existe ação global de aceitar ou descartar tudo nesta tela.

### Interface e navegação

- Telas migradas e padronizadas com Jetpack Compose e Material 3.
- Gerenciadores de arquivos utilizam tamanhos, espaçamentos, cores e ícones consistentes.
- Barras inferiores e botões respeitam a área dos botões de navegação do Android.
- O ícone do aplicativo foi restaurado a partir do APK Editor original, mantendo desenho e cores.
- A tela **Git Status** voltou a mostrar a foto do perfil do autor de cada commit.

### Edição completa

- Navegação pelos arquivos reais do APK e por workspaces Smali derivados de arquivos DEX.
- Edição de Manifest, XML/AXML, textos e recursos de strings.
- Novo fragment **Recursos** para editar cores, dimensões, booleanos, inteiros, plurais e listas tipadas, com validação antes de salvar.
- Visualizador/editor interno de PNG, JPG e WebP com zoom, redimensionamento, transparência geral e remoção de fundo por tolerância de cor.
- Substituição, exportação, adição e exclusão de arquivos.
- Registro centralizado das modificações para reconstrução posterior.
- Integração dos fragments de strings, arquivos, recursos tipados, manifest e diferenças em uma interface Compose.

### Reconstrução e assinatura

- Manifest, XML binário e `resources.arsc` passam por uma única base de compilação para que uma categoria de recurso não sobrescreva a outra.
- A reconstrução roda em um serviço em primeiro plano: sair da tela não interrompe o trabalho, o andamento fica na notificação e pode ser cancelado.
- O estado da reconstrução e o caminho do resultado ficam persistidos para a tela se reconectar ao serviço.
- Assinatura com chave de teste ou KeyStore personalizado.
- Gerenciamento de certificados, aliases e senhas de chave.

### Patches e projetos salvos

- O motor de patches aceita adição, remoção, busca/substituição literal ou regex, captura em variáveis, desvios condicionais, `GOTO`, regras vazias e mesclagem segura de arquivos.
- Metadados de versão e pacote são validados; regras executáveis ou capazes de alterar assinatura são recusadas por segurança.
- Ao final, o aplicativo apresenta um relatório das regras aplicadas.
- Projetos salvos guardam a impressão SHA-256, tamanho e data do APK original.
- APK ausente ou alterado bloqueia a abertura até que o mesmo original seja selecionado novamente; as edições armazenadas são preservadas.
- A gravação do arquivo de estado é atômica para reduzir risco de corrupção se o processo for interrompido.

## Comparação aplicada com o APK original

O código decompilado foi usado como referência de comportamento, não como substituição direta do projeto. Entre os comportamentos recuperados estão:

- separação real entre arquivos, imagens e áudios;
- agrupamento de variantes de imagens por nome;
- miniaturas lidas diretamente das entradas do ZIP;
- reprodução e substituição de áudio;
- idiomas baseados nos recursos realmente presentes;
- rastreamento individual das alterações antes da reconstrução.

## Tecnologias

- Kotlin
- Jetpack Compose
- Material 3
- AndroidX Fragments e ViewPager2
- Gradle com Kotlin DSL
- Sora Editor para edição de código
- `apksig` para assinatura de APKs
- Bouncy Castle para operações criptográficas
- Gson para dados JSON
- AXML e ferramentas de recursos Android

## Requisitos

- JDK 17
- Android Studio compatível com o projeto
- Android SDK instalado
- Dispositivo ou emulador Android API 24 ou superior

## Compilação

Clone o repositório:

```bash
git clone https://github.com/FabioSilva11/Apk-Editor-PLus.git
cd Apk-Editor-PLus
```

No Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

O APK será gerado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Configuração para computador com 4 GB de RAM

Use somente um worker, limite a memória da JVM e não mantenha o daemon ativo:

```powershell
$env:GRADLE_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest assembleDebug
```

## Estado atual

- Build debug e testes unitários concluídos com 1 GB de heap, um worker e sem daemon.
- APK debug verificado com assinatura v2 válida.
- Testes unitários abrangem o histórico de diferenças e o catálogo de idiomas disponíveis.
- A versão anterior foi conferida em aparelho Android físico; as funções adicionadas nesta revisão ainda precisam da validação completa no aparelho.
- O projeto ainda está em evolução e pode conter formatos de recursos ou APKs incompatíveis.

## Contribuindo

Relatos de erro e contribuições podem ser enviados pelas Issues e Pull Requests do repositório. Inclua o modelo do aparelho, versão do Android, operação realizada e o log do erro quando disponível.

## Licença

Consulte o arquivo [LICENSE](LICENSE).
