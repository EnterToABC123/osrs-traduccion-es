# OSRS Traducción Español

Plugin de RuneLite que traduce Old School RuneScape al español: diálogos, opciones, menús, mensajes del juego,
diarios de misiones y de logros, libros, objetos y más.

Está basado en [OSRS Translate PT-BR](https://github.com/walterrezende12-tech/Traducao-OSRS-BR) de Walter Rezende
(licencia BSD-2) y usa sus diccionarios, con una revisión completa del español.

## Diferencias con el plugin original

- **Español por defecto**, con nombre y configuración propios (puede convivir con el original).
- **Diccionario en español revisado**: más de 2.100 correcciones (mayúsculas, signos `¡` `¿`, traducciones
  a medias o equivocadas) y más de 600 entradas nuevas.
- **Mensajes del chat con color**: el juego envía algunos mensajes con códigos como `@mes_hl_cya@`;
  ahora se traducen y conservan su color.
- **Registro de textos sin traducir** (opcional, desactivado por defecto): guarda en
  `~/.runelite/osrs-traduccion/sem-traducao/ESP.jsonl` lo que el juego muestra y no está en el diccionario,
  para poder traducirlo. Nada se envía por internet.

## Diccionarios

Los diccionarios se descargan de
[EnterToABC123/osrs-translate-translations](https://github.com/EnterToABC123/osrs-translate-translations),
se verifican con SHA-256 y se guardan en `~/.runelite/osrs-traduccion/translations/`.
El plugin hace peticiones a GitHub (api.github.com y raw.githubusercontent.com) para descargarlos;
GitHub ve tu dirección IP como en cualquier descarga. No se envía ningún dato de tu cuenta ni del juego.

## Licencia

BSD-2-Clause. Código original © Walter Rezende.
