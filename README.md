# OSRS Traducción Español

Plugin de RuneLite que traduce Old School RuneScape al español: diálogos, opciones, menús, mensajes del juego,
diarios de misiones y de logros, libros, objetos y más.

> **🚧 En mejora constante:** la traducción todavía no es perfecta. Se actualiza **todos los días**,
> corrigiendo errores y traduciendo textos nuevos del juego (quests, Sailing, menús...). Si ves algo en
> inglés o mal traducido, puedes avisar en [Issues](https://github.com/EnterToABC123/osrs-traduccion-es/issues).
> Las traducciones nuevas llegan solas: no hace falta reinstalar el plugin.

## Cómo instalarlo

1. En RuneLite, abre el panel lateral y haz clic en el icono del enchufe (**Plugin Hub**).
2. Busca **OSRS Traducción Español** y pulsa **Install**.

Los nombres de lugares, objetos, jefes, minijuegos y personajes se dejan en inglés a propósito, para que
coincidan con la wiki y con lo que usan los demás jugadores. Los chats y nombres de otros jugadores nunca
se traducen ni se guardan.

## Origen

Está basado en [OSRS Translate PT-BR](https://github.com/walterrezende12-tech/Traducao-OSRS-BR) de Walter Rezende
(licencia BSD-2) y usa sus diccionarios, con una revisión completa del español.

## Diferencias con el plugin original

- **Español por defecto**, con nombre y configuración propios (puede convivir con el original).
- **Diccionario en español revisado**: más de 2.100 correcciones (mayúsculas, signos `¡` `¿`, traducciones
  a medias o equivocadas) y más de 600 entradas nuevas.
- **Mensajes del chat con color**: el juego envía algunos mensajes con códigos como `@mes_hl_cya@`;
  ahora se traducen y conservan su color.
- **Registro de textos sin traducir** (opcional, desactivado por defecto): guarda en la carpeta
  `sem-traducao` (dentro de la carpeta de datos del plugin en RuneLite) lo que el juego muestra y no está
  en el diccionario, para poder traducirlo. Nada se envía por internet.
- **Modo de pruebas** (Modo desenvolvedor + fuente Local): lee los JSON de la carpeta `local`
  dentro de la carpeta de datos del plugin y los recarga al guardarlos.

## Diccionarios

Los diccionarios se descargan de
[EnterToABC123/osrs-translate-translations](https://github.com/EnterToABC123/osrs-translate-translations),
se verifican con SHA-256 y se guardan en la carpeta `translations` dentro de la carpeta de datos del plugin.
Todo el acceso a archivos usa la API `Filepath` de RuneLite.
El plugin hace peticiones a GitHub (api.github.com y raw.githubusercontent.com) para descargarlos;
GitHub ve tu dirección IP como en cualquier descarga. No se envía ningún dato de tu cuenta ni del juego.

## Licencia

BSD-2-Clause. Código original © Walter Rezende.
