package org.jenkinsci.plugins.workflow.support;

import hudson.XmlFile;
import hudson.util.AtomicFileWriter;
import hudson.util.XStream2;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.StandardOpenOption;

/**
 * Utilities to assist with IO and in some cases improve performance specifically for pipeline.
 */
public class PipelineIOUtils {
    /**
     * Convenience method to transparently write data directly or atomicly using {@link hudson.util.AtomicFileWriter}.
     * @param toWrite Object to write to file
     * @param location File to write object to
     * @param xstream xstream to use for output
     * @param atomicWrite If true, do an atomic write, otherwise do a direct write to file.
     * @throws IOException
     * @deprecated use {@link #writeByXStream(Object, File, XStream2, boolean, boolean)}
     */
    @Deprecated
    public static void writeByXStream(@Nonnull Object toWrite, @Nonnull File location, @Nonnull XStream2 xstream, boolean atomicWrite) throws IOException {
        writeByXStream(toWrite, location, xstream, atomicWrite, atomicWrite);
    }

    /**
     * Convenience method to transparently write data directly or atomically using {@link
     * hudson.util.AtomicFileWriter}, with or without {@link FileChannel#force} (i.e., {@code
     * fsync} or {@code FlushFileBuffers}).
     *
     * @param toWrite Object to write to file
     * @param location File to write object to
     * @param xstream xstream to use for output
     * @param atomicWrite use {@link AtomicFileWriter} to write the file.
     * @param force If true, call {@link FileChannel#force} (i.e., {@code fsync} or {@code
     *     FlushFileBuffers}) after writing the file.
     * @throws IOException
     */
    public static void writeByXStream(
            @Nonnull Object toWrite,
            @Nonnull File location,
            @Nonnull XStream2 xstream,
            boolean atomicWrite,
            boolean force)
            throws IOException {
        if (atomicWrite) {
            XmlFile file = new XmlFile(xstream, location);
            file.write(toWrite, force);
        } else {
            try(OutputStream os = new BufferedOutputStream(
                    Files.newOutputStream(location.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))){
                xstream.toXMLUTF8(toWrite, os); // No atomic nonsense, just write and write and write!
            } catch (InvalidPathException ipe) {
                throw new IOException(ipe);
            }
        }
    }
}
