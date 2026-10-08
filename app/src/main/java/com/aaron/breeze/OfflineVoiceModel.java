package com.aaron.breeze;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** Installs bundled recognition data into the private path required by Vosk. */
final class OfflineVoiceModel {
    private static final String ASSET_ROOT = "voice-model";
    private static final String DIRECTORY = "voice-model-small-en-us-0.15-v1";

    private OfflineVoiceModel() { }

    static File prepare(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        File marker = new File(directory, ".complete");
        if (marker.isFile() && valid(directory)) return directory;
        copy(context.getAssets(), ASSET_ROOT, directory);
        if (!valid(directory)) throw new IOException("Bundled English voice model is incomplete");
        checkInterrupted();
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(DIRECTORY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        return directory;
    }

    private static boolean valid(File directory) {
        String[] required = {"am/final.mdl", "conf/model.conf", "conf/mfcc.conf",
                "graph/HCLr.fst", "graph/Gr.fst"};
        for (String name : required) {
            File file = new File(directory, name);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    private static void copy(AssetManager assets, String source, File target) throws IOException {
        checkInterrupted();
        String[] children = assets.list(source);
        if (children == null) throw new IOException("Cannot read bundled voice model");
        if (children.length != 0) {
            if (!target.isDirectory() && !target.mkdirs())
                throw new IOException("Cannot create private voice model directory");
            for (String child : children) copy(assets, source + "/" + child, new File(target, child));
        } else {
            try (InputStream input = assets.open(source);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkInterrupted();
                    output.write(buffer, 0, count);
                }
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Voice start cancelled");
    }
}
