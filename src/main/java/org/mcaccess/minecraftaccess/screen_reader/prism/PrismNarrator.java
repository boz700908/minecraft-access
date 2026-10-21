package org.mcaccess.minecraftaccess.screen_reader.prism;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * A minimal binding to Prism, the cross platform screen reader and text to speech library.
 *
 * <p>Prism ships one self contained native library per platform, which exports a plain C interface. The library is kept
 * in the resources of this mod, written out next to the game directory, loaded, and then asked for the best backend it
 * can start by itself, that being a running screen reader when there is one and a text to speech engine otherwise. The
 * interface is reached through the foreign function and memory API of the JVM, so no JNI and no additional download is
 * involved.
 *
 * <p>Only speaking, stopping and shutting down are used, the mod narrates short strings and has no use for the braille,
 * voice or memory rendering parts of the interface.
 *
 * <p>Any failure is reported by {@link #create(Path)} returning {@code null}, so that a missing library, a platform
 * without the foreign API, or a machine with no speech backend at all falls back to the screen reader of the platform
 * instead of breaking the game.
 */
@Slf4j
public final class PrismNarrator implements AutoCloseable {
    /**
     * The directory of the mod resources holding the native libraries.
     */
    private static final String RESOURCE_ROOT = "mcaccess/prism";
    /**
     * The directory, inside the game directory, the libraries are written to.
     */
    private static final String LIBRARY_DIRECTORY = "minecraft_access/prism-native";
    /**
     * The system property naming the platform directory to use, for a platform the detection below gets wrong.
     */
    private static final String PLATFORM_PROPERTY = "minecraftaccess.prism.platform";
    /**
     * Names a Prism library already on disk to load instead of the one in the resources of the mod.
     *
     * <p>iOS is the reason this exists: the system only loads code that was signed as part of the application, so
     * a library written out next to the game directory at runtime is refused. A launcher that ships the library
     * inside its own bundle can point this property at it.
     */
    private static final String LIBRARY_PROPERTY = "minecraftaccess.prism.library";
    /**
     * The value of {@code PRISM_OK}.
     */
    private static final int OK = 0;
    /**
     * The value of {@code PRISM_ERROR_NOT_IMPLEMENTED}, said by a backend for a call it cannot serve.
     */
    private static final int NOT_IMPLEMENTED = 3;

    private final Arena arena;
    private final MemorySegment context;
    private final MemorySegment backend;
    private final Handles handles;
    private final Object lock = new Object();
    private float rate = Float.NaN;
    private boolean closed;

    private PrismNarrator(Arena arena, MemorySegment context, MemorySegment backend, Handles handles) {
        this.arena = arena;
        this.context = context;
        this.backend = backend;
        this.handles = handles;
    }

    /**
     * Starts Prism, when it supports this platform and finds a backend to speak with.
     *
     * <p>This loads native code, so it is worth doing once, at startup, and keeping the result.
     *
     * @param baseDirectory the directory of the game, where the native libraries are written to
     * @return a narrator, or {@code null} when Prism is unusable and the caller has to fall back
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    public static PrismNarrator create(Path baseDirectory) {
        Path named = libraryFile();
        if (named != null) {
            try {
                return load(List.of(named));
            } catch (IOException | RuntimeException | LinkageError e) {
                log.error("Unable to start Prism from {}, the screen reader of the platform is used instead", named, e);
                return null;
            }
        }

        List<NativeLibrary> candidates = NativeLibrary.candidates();
        if (candidates.isEmpty()) {
            log.info(
                    "Prism has no native library for {} on {}, the screen reader of the platform is used instead",
                    System.getProperty("os.name"),
                    System.getProperty("os.arch")
            );
            return null;
        }

        for (NativeLibrary library : candidates) {
            try {
                return load(extract(baseDirectory, library));
            } catch (IOException | RuntimeException | LinkageError e) {
                log.warn("Unable to start the Prism library for {}", library.directory(), e);
            }
        }

        log.error("Unable to start Prism, the screen reader of the platform is used instead");
        return null;
    }

    /**
     * The library named by {@link #LIBRARY_PROPERTY}, when it is set and readable.
     *
     * @return the library to load, or {@code null} to use the one in the resources of the mod
     */
    private static Path libraryFile() {
        String path = System.getProperty(LIBRARY_PROPERTY);
        if (path == null || path.isBlank()) return null;

        Path file = Path.of(path);
        if (Files.isReadable(file)) return file;

        log.warn("{} does not name a readable file, using the library in the resources of the mod instead", path);
        return null;
    }

    private static PrismNarrator load(List<Path> files) throws IOException {
        for (Path file : files) {
            System.load(file.toAbsolutePath().toString());
        }

        // The handles outlive each call they make, so the arena has to be shared rather than confined
        Arena arena = Arena.ofShared();
        SymbolLookup lookup = SymbolLookup.libraryLookup(files.getLast(), arena);
        Linker linker = Linker.nativeLinker();

        Handles handles = new Handles(
                downcall(linker, lookup, "prism_backend_speak", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)),
                downcall(linker, lookup, "prism_backend_stop", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS)),
                optional(linker, lookup, "prism_backend_set_rate", FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT)),
                downcall(linker, lookup, "prism_backend_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
                downcall(linker, lookup, "prism_shutdown", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
                downcall(linker, lookup, "prism_error_string", FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT)),
                optional(linker, lookup, "prism_backend_name", FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS))
        );

        // No configuration is passed, which leaves Prism with its default registry and lets it choose the backend the
        // machine itself proposes, rather than one we would have to know about
        MemorySegment context = invocation(
                downcall(linker, lookup, "prism_init", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                MemorySegment.NULL
        );
        if (context.equals(MemorySegment.NULL)) {
            arena.close();
            throw new IllegalStateException("Prism was not initialized");
        }

        MemorySegment backend = invocation(
                downcall(linker, lookup, "prism_registry_create_best", FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS)),
                context
        );
        if (backend.equals(MemorySegment.NULL)) {
            invocation(handles.shutdown(), context);
            arena.close();
            throw new IllegalStateException("Prism found no backend able to speak on this machine");
        }

        log.info("Started the Prism backend {}", nameOf(handles.name(), backend));
        return new PrismNarrator(arena, context, backend, handles);
    }

    private static List<Path> extract(Path baseDirectory, NativeLibrary library) throws IOException {
        Path directory = baseDirectory.resolve(LIBRARY_DIRECTORY).resolve(library.directory());
        Files.createDirectories(directory);

        List<Path> files = new ArrayList<>();
        for (String name : library.files()) {
            String resource = "/" + RESOURCE_ROOT + "/" + library.directory() + "/" + name;
            Path target = directory.resolve(name);
            try (InputStream stream = PrismNarrator.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IOException("The mod is missing the Prism library " + resource);
                }
                write(stream.readAllBytes(), target);
            }
            files.add(target);
        }
        return files;
    }

    private static void write(byte[] content, Path target) throws IOException {
        if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), content)) {
            return;
        }
        Files.write(target, content);
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(
                lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Prism does not export " + name)),
                descriptor
        );
    }

    private static MethodHandle optional(Linker linker, SymbolLookup lookup, String name,
                                         FunctionDescriptor descriptor) {
        return lookup.find(name)
                .map(symbol -> linker.downcallHandle(symbol, descriptor))
                .orElse(null);
    }

    /**
     * Calls into the library, giving up rather than taking the game down when the call fails.
     *
     * @param handle the function to call
     * @param arguments its arguments, in order
     * @return what it returned, the null segment when nothing came back or the call failed
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private static MemorySegment invocation(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments) instanceof MemorySegment segment
                    ? segment
                    : MemorySegment.NULL;
        } catch (Throwable e) {
            log.error("A call into Prism failed", e);
            return MemorySegment.NULL;
        }
    }

    /**
     * Calls a function returning a Prism error code.
     *
     * @param handle the function to call
     * @param arguments its arguments, in order
     * @return the error code, or a negative value when the call itself failed
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private static int errorOf(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments) instanceof Integer error ? error : -1;
        } catch (Throwable e) {
            log.error("A call into Prism failed", e);
            return -1;
        }
    }

    private static String nameOf(MethodHandle handle, MemorySegment backend) {
        if (handle == null) {
            return "unknown";
        }

        MemorySegment name = invocation(handle, backend);
        return name.equals(MemorySegment.NULL)
                ? "unknown"
                : name.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }

    /**
     * Speaks the given text.
     *
     * <p>Prism does not synchronize its backends, and the mod narrates from whichever thread happens to notice
     * something, so every call is serialized here.
     *
     * @param text what to speak
     * @param interrupt whether the speech in progress is dropped
     */
    public void narrate(String text, boolean interrupt) {
        if (text.isEmpty()) {
            return;
        }

        synchronized (lock) {
            if (closed) {
                return;
            }

            // The text only has to outlive the call, so a confined arena is enough and the memory goes with it
            try (Arena call = Arena.ofConfined()) {
                byte[] content = text.getBytes(StandardCharsets.UTF_8);
                MemorySegment pointer = call.allocate(content.length + 1L);
                MemorySegment.copy(MemorySegment.ofArray(content), 0, pointer, 0, content.length);

                int error = errorOf(handles.speak(), backend, pointer, interrupt);
                if (error != OK) {
                    log.warn("Prism could not speak \"{}\" ({}: {})", text, error, describe(error));
                }
            }
        }
    }

    /**
     * Sets the speech rate.
     *
     * @param value the rate, between zero and one, where a half is what the backend considers normal; anything else
     *              is ignored
     */
    public void setRate(float value) {
        synchronized (lock) {
            if (closed || handles.setRate() == null || value == rate || value < 0 || value > 1) {
                return;
            }

            rate = value;
            int error = errorOf(handles.setRate(), backend, value);
            if (error != OK && error != NOT_IMPLEMENTED) {
                log.warn("Prism could not set the speech rate ({}: {})", error, describe(error));
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;

            errorOf(handles.stop(), backend);
            invocation(handles.free(), backend);
            invocation(handles.shutdown(), context);
            arena.close();
        }
    }

    private String describe(int error) {
        MemorySegment message = invocation(handles.errorString(), error);
        return message.equals(MemorySegment.NULL)
                ? "no description"
                : message.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }

    /**
     * The native library Prism publishes for one platform, laid out as it is in the resources of the mod.
     *
     * @param directory the directory in the resources holding the files of the platform
     * @param files the files to load, in the order they have to be loaded, the last one being the library itself
     */
    private record NativeLibrary(String directory, List<String> files) {
        private static NativeLibrary detect() {
            String named = System.getProperty(PLATFORM_PROPERTY);
            if (named != null) {
                NativeLibrary library = of(named);
                if (library != null) {
                    return library;
                }
                log.warn(
                        "Prism has no library for the platform named {}.{}, ignoring the property",
                        PLATFORM_PROPERTY,
                        named
                );
            }

            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean arm = architecture.contains("aarch64") || architecture.contains("arm64");

            if (name.contains("windows")) {
                return of(arm ? "windows-aarch64" : "windows-x86_64");
            }
            // Before the Apple platforms, since a runtime on a device tends to call itself iOS and macOS both
            if (name.contains("ios") || name.contains("iphone") || name.contains("ipad")) {
                return of("ios-arm64");
            }
            if (name.contains("mac") || name.contains("darwin")) {
                return of("macos-universal");
            }
            if (name.contains("linux")) {
                return of(arm ? "linux-aarch64" : "linux-x86_64");
            }
            return null;
        }

        /**
         * The platforms to try, best guess first. A JVM running on an iOS device often describes itself as
         * Darwin, which would pick the macOS library: the two use the same file name but not the same system
         * frameworks, so the other one is tried when the first does not load.
         *
         * @return the libraries to try in order, empty when this platform is not supported at all
         */
        private static List<NativeLibrary> candidates() {
            NativeLibrary detected = detect();
            if (detected == null) return List.of();

            String alternative = switch (detected.directory()) {
                case "macos-universal" -> "ios-arm64";
                case "ios-arm64" -> "macos-universal";
                default -> null;
            };
            return alternative == null ? List.of(detected) : List.of(detected, of(alternative));
        }

        private static NativeLibrary of(String directory) {
            return switch (directory) {
                case "windows-x86_64", "windows-aarch64" -> new NativeLibrary(directory, List.of("prism.dll"));
                case "linux-x86_64", "linux-aarch64" -> new NativeLibrary(directory, List.of("libprism.so"));
                case "macos-universal", "ios-arm64" -> new NativeLibrary(directory, List.of("libprism.dylib"));
                default -> null;
            };
        }
    }

    /**
     * The functions of the library this class calls.
     *
     * <p>Those a backend may not implement are null when the library does not export them at all.
     *
     * @param speak {@code prism_backend_speak}
     * @param stop {@code prism_backend_stop}
     * @param setRate {@code prism_backend_set_rate}
     * @param free {@code prism_backend_free}
     * @param shutdown {@code prism_shutdown}
     * @param errorString {@code prism_error_string}
     * @param name {@code prism_backend_name}
     */
    private record Handles(MethodHandle speak, MethodHandle stop, MethodHandle setRate, MethodHandle free,
                           MethodHandle shutdown, MethodHandle errorString, MethodHandle name) {
    }
}
