# Cómo ayudar con la traducción

¡Gracias por querer ayudar! Hay dos formas. Toda sugerencia se revisa antes de publicarse.

## 1. La fácil: sugerir una traducción

1. Crea una cuenta gratis en GitHub si no tienes.
2. Ve a [Issues → New issue](https://github.com/EnterToABC123/osrs-traduccion-es/issues/new/choose)
   y elige **✍️ Sugerir una traducción**.
3. Pega el texto en inglés tal como sale en el juego y, si quieres, cómo debería decirse en español.

Con eso basta. Lo revisamos, lo añadimos y llega solo a todos los jugadores (no hay que reinstalar nada).

## 2. Para quien sabe usar GitHub: editar los diccionarios

Las traducciones están en el repositorio
[EnterToABC123/osrs-translate-translations](https://github.com/EnterToABC123/osrs-translate-translations),
carpeta `ESP`. Cada archivo es un JSON `"inglés": "español"`:

| Archivo | Qué contiene |
|---|---|
| `translations.json` | Diálogos, ventanas e interfaces |
| `translations_game_message.json` | Mensajes del chat del juego |
| `translations_menu.json` | Opciones del menú de clic derecho |
| `translations_overhead.json` | Textos sobre la cabeza de personajes |
| `translations_quests.json` | Diario de misiones y de logros |
| `translations_settings.json` | Ajustes |
| `translations_skills.json` | Guías de habilidades |
| `translations_welcome.json` | Pantalla de bienvenida e inicio de sesión |

Edita el archivo y manda un **Pull Request**. No toques `manifest.json`: se regenera al publicar.

## Reglas de estilo

- **Se quedan en inglés:** nombres de lugares, objetos, jefes, minijuegos, personajes, habilidades
  (Sailing, Slayer...), hechizos, plegarias y títulos de canciones. Así coinciden con la wiki y con lo que
  usan los demás jugadores.
- **Comodines:** `[number]`, `[player]`, `[value]`... se rellenan solos, en el mismo orden. Deben aparecer
  los mismos en inglés y en español:
  `"You need [number] coins to convert oak logs."` → `"Necesitas [number] monedas para convertir troncos de roble."`
- Español neutro, tuteando al jugador ("Necesitas", no "Necesita").
- Signos de apertura `¡` y `¿`.
- Copia el inglés **exacto**, con sus mayúsculas y puntos: si cambia una letra, no se encuentra.
- Nunca incluyas chats ni nombres de otros jugadores.
