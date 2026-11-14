package com.osrstraduccion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Guarda en un archivo local los textos que el juego mostró y que no están en el diccionario,
 * para poder traducirlos después. Una línea JSON por texto, sin repetidos (también entre sesiones).
 *
 * Archivo: <carpeta de datos del plugin>/sem-traducao/<idioma>.jsonl
 * No se envía nada por internet.
 */
@Slf4j
final class MissingTranslationRecorder
{
    private static final int MAX_PER_SESSION = 20000;
    private static final int MAX_TEXT_LENGTH = 2000;
    /** Al menos una palabra de 3 letras: descarta números, nombres cortos, iconos, etc. */
    private static final Pattern HAS_WORD = Pattern.compile("[A-Za-z]{3,}");
    /** Solo números, signos y unidades ("1,234", "50%", "x3", "12:30"). */
    private static final Pattern ONLY_NUMBERS = Pattern.compile("^[\\d\\s.,:;/%()+\\-x*#kmKM]+$");

    private final Filepath directory;
    private final Filepath file;
    private final Gson gson;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "osrs-traduccion-missing-recorder");
        t.setDaemon(true);
        return t;
    });
    private volatile int recordedThisSession;

    MissingTranslationRecorder(Filepath directory, String languageFolder, Gson gson)
    {
        this.directory = directory;
        this.file = directory.joinSegment(languageFolder + ".jsonl");
        this.gson = gson;
        writer.execute(this::loadExisting);
    }

    Filepath getFile()
    {
        return file;
    }

    /**
     * @param fileName diccionario donde debería ir el texto (p. ej. translations.json)
     * @param source   de dónde salió: interfaz, overhead o tipo de mensaje del chat
     * @param text     texto en inglés tal como se buscó en el diccionario
     */
    void record(String fileName, String source, String text)
    {
        if (text == null || fileName == null)
        {
            return;
        }
        String clean = text.trim();
        if (clean.length() < 4 || clean.length() > MAX_TEXT_LENGTH
            || !HAS_WORD.matcher(clean).find() || ONLY_NUMBERS.matcher(clean).matches())
        {
            return;
        }
        if (recordedThisSession >= MAX_PER_SESSION || !seen.add(fileName + '\u0000' + clean))
        {
            return;
        }
        recordedThisSession++;

        JsonObject line = new JsonObject();
        line.addProperty("arquivo", fileName);
        line.addProperty("origem", source);
        line.addProperty("texto", clean);
        line.addProperty("data", Instant.now().toString());
        String json = gson.toJson(line);
        writer.execute(() -> append(json));
    }

    /** Deja de aceptar escrituras; las pendientes terminan solas en el hilo del registro. */
    void shutDown()
    {
        writer.shutdown();
    }

    private void loadExisting()
    {
        if (!file.isFile())
        {
            return;
        }
        try (BufferedReader in = file.openBufferedReader())
        {
            String line;
            while ((line = in.readLine()) != null)
            {
                try
                {
                    JsonObject o = gson.fromJson(line, JsonObject.class);
                    seen.add(o.get("arquivo").getAsString() + '\u0000' + o.get("texto").getAsString());
                }
                catch (Exception ignored)
                {
                    // línea dañada: se ignora
                }
            }
        }
        catch (IOException e)
        {
            log.warn("No se pudo leer el registro de textos sin traducir: {}", file, e);
        }
    }

    private void append(String json)
    {
        try
        {
            directory.createDirectories();
            try (Writer out = file.openWriter(StandardOpenOption.CREATE, StandardOpenOption.APPEND))
            {
                out.write(json);
                out.write('\n');
            }
        }
        catch (IOException e)
        {
            log.warn("No se pudo escribir en {}", file, e);
        }
    }
}
