package com.osrstraduccion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Diario de diagnóstico (solo en modo desarrollador): todo lo que el plugin ve y lo que hace con ello,
 * en orden, con el texto original (con sus colores) y el resultado. Sirve para encontrar qué falta por
 * traducir y por qué algo no se traduce.
 *
 * Archivo: <carpeta de datos del plugin>/diagnostico/<fecha>.jsonl. Nunca guarda chats de jugadores.
 * No se envía nada por internet.
 */
@Slf4j
final class DiagnosticJournal
{
    private static final int MAX_PER_SESSION = 50000;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Filepath directory;
    private final Filepath file;
    private final Gson gson;
    /** Eventos que se repiten mucho (interfaces, menús): solo la primera vez por sesión. */
    private final Set<String> once = ConcurrentHashMap.newKeySet();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "osrs-traduccion-diagnostico");
        t.setDaemon(true);
        return t;
    });
    private volatile int writtenThisSession;

    DiagnosticJournal(Filepath directory, Gson gson)
    {
        this.directory = directory;
        this.file = directory.joinSegment(LocalDate.now() + ".jsonl");
        this.gson = gson;
    }

    Filepath getFile()
    {
        return file;
    }

    /**
     * @param event    chat, menu, interfaz, overhead, repaso, sesion...
     * @param type     tipo de mensaje o número de interfaz
     * @param status   traducido, sin_traducir, ignorado, info
     * @param original texto original tal como llegó (con colores)
     * @param result   traducción aplicada (o null)
     */
    void write(String event, String type, String status, String original, String result)
    {
        if (writtenThisSession >= MAX_PER_SESSION)
        {
            return;
        }
        writtenThisSession++;
        JsonObject line = new JsonObject();
        line.addProperty("hora", LocalTime.now().format(HOUR));
        line.addProperty("evento", event);
        if (type != null)
        {
            line.addProperty("tipo", type);
        }
        line.addProperty("estado", status);
        if (original != null)
        {
            line.addProperty("original", cut(original));
        }
        if (result != null)
        {
            line.addProperty("resultado", cut(result));
        }
        String json = gson.toJson(line);
        writer.execute(() -> append(json));
    }

    /** Igual que write, pero solo la primera vez por sesión para ese evento + texto. */
    void writeOnce(String event, String type, String status, String original, String result)
    {
        if (once.add(event + '\u0000' + type + '\u0000' + status + '\u0000' + original))
        {
            write(event, type, status, original, result);
        }
    }

    void shutDown()
    {
        writer.shutdown();
    }

    private static String cut(String text)
    {
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) + "..." : text;
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
