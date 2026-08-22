# Relatório de equivalência com APK Editor 4.5.2

Referência analisada: APK original `com.gmail.heagoo.apkeditor.pro` 4.5.2, decompilado com JADX e recursos extraídos para comparação de lógica.

## Funções visíveis do projeto

| Área | Equivalência implementada |
|---|---|
| Edição comum | pacote, versão, nome, SDK, local de instalação, ícone e renomeação opcional de referências DEX |
| Strings | leitura da tabela `resources.arsc`, pesquisa, edição por localidade e reconstrução do recurso |
| Manifest | decodificação binária, edição estruturada e textual, recompilação AXML estrita e exportação |
| Arquivos | navegação, pesquisa por nome/conteúdo, adicionar, substituir, excluir, exportar e editar texto/XML |
| DEX/Smali | desmontagem DEX, edição Smali e remontagem com API configurável e um trabalhador |
| Reconstrução | substituições/exclusões centralizadas, preservação ZIP e `resources.arsc` sem compressão |
| Assinatura | chaves de teste ou personalizadas, esquemas v1, v2, v3 e v4 com `.idsig`, além de verificação |
| Projetos | persistência e retomada das alterações pendentes |
| Patch e servidor | aplicação de subconjunto de mudanças e acesso ao projeto pela rede local |

## Configurações portadas e integradas

- Tema claro, escuro ou do sistema.
- Ordenação de aplicativos instalados por nome ou data.
- Decodificação completa, parcial ou perguntada no momento da pesquisa.
- API Smali, diretório de trabalho e habilitação da edição Smali.
- Quebra de linha, números de linha, fonte, limite de arquivo grande, barra de símbolos e editor externo.
- Tema e cores personalizadas do editor, incluindo fundo, linhas e nove grupos de sintaxe.
- Confirmação de reconstrução em todos os modos de edição.
- Chave automática de teste ou seleção segura de chave personalizada durante a reconstrução; senhas não são persistidas.
- Padrão do nome do APK, sobrescrita ou renomeação automática e assinaturas v1 a v4.
- Limpeza segura dos arquivos temporários internos e dos diretórios de trabalho pertencentes ao app.

## Decisões de escopo

- `Home settings`/estilos da tela inicial foram removidos conforme solicitado.
- A seleção de idioma do original não foi exposta porque esta versão possui somente os recursos em português; uma opção sem segundo idioma não teria efeito.
- Os comandos separados para apagar históricos antigos não foram copiados porque as pesquisas Compose não persistem histórico. A limpeza de arquivos temporários permanece funcional.
- A chave proprietária do APK original não foi reutilizada. O app oferece chave de teste e chaves PKCS12/JKS criadas ou importadas pelo usuário.

## Padronização visual

As telas de arquivos usam componentes Material 3 comuns, linhas compactas, dimensões consistentes e ícones Compose específicos para pasta, APK, XML, texto, imagem, áudio, DEX/Smali, arquivo compactado e formato desconhecido. As telas principais, editores, projetos, chaves, assinatura, verificação e configurações foram migradas para Compose mantendo o editor Sora dentro de `AndroidView`.

## Verificação

- Compilação Kotlin com heap máximo de 1 GB, sem daemon persistente e um trabalhador: aprovada.
- Testes unitários: aprovados.
- Quatro testes instrumentados no aparelho `2312DRA50G` (`5ffa6400`): aprovados, sem falhas.
- O teste real reconstrói o próprio APK, altera recursos/manifesto, assina, verifica a assinatura, confirma `resources.arsc` como `STORED` e valida a geração da assinatura v4 `.idsig`.
