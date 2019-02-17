package io.muserver.handlers;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.muserver.handlers.ResourceType.DEFAULT_EXTENSION_MAPPINGS;

/**
 * A handler to serve static content. To create a handler, using {@link ResourceHandlerBuilder#fileOrClasspath(String, String)},
 * {@link ResourceHandlerBuilder#classpathHandler(String)}, {@link ResourceHandlerBuilder#fileHandler(File)} or one of its variants.
 */
public class ResourceHandler implements MuHandler {
    private static final Logger log = LoggerFactory.getLogger(ResourceHandler.class);

    private final Map<String, ResourceType> extensionToResourceType;
    private final String pathToServeFrom;
    private final String defaultFile;
    private final ResourceProviderFactory resourceProviderFactory;

    ResourceHandler(ResourceProviderFactory resourceProviderFactory, String pathToServeFrom, String defaultFile, Map<String, ResourceType> extensionToResourceType) {
        this.resourceProviderFactory = resourceProviderFactory;
        this.pathToServeFrom = pathToServeFrom;
        this.extensionToResourceType = extensionToResourceType;
        this.defaultFile = defaultFile;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String requestPath = request.relativePath();
        if (!requestPath.startsWith(pathToServeFrom)) {
            return false;
        }
        if (requestPath.endsWith("/") && defaultFile != null) {
            requestPath += defaultFile;
        }

        String pathWithoutWebPrefix = pathToServeFrom.equals("/")
            ? requestPath
            : requestPath.substring(pathToServeFrom.length());

        String decodedRelativePath = Mutils.urlDecode(pathWithoutWebPrefix);
        ResourceProvider provider = resourceProviderFactory.get(decodedRelativePath);
        if (!provider.exists()) {
            return false;
        }

//        log.info("<< " + request + " - " + request.headers());

        if (provider.isDirectory()) {
            String goingTo = request.uri().getPath() + "/";
            response.redirect(goingTo);
        } else {
            String filename = requestPath.substring(requestPath.lastIndexOf('/'));
            Date lastModified = provider.lastModified();
            Long totalSize = provider.fileSize();
            addHeaders(response, filename, totalSize, lastModified);
            boolean sendBody = request.method() != Method.HEAD;

            String ims = request.headers().get(HeaderNames.IF_MODIFIED_SINCE);
            if (ims != null) {
                try {
                    long lastModTime = lastModified.getTime() / 1000;
                    long lastAccessed = Mutils.fromHttpDate(ims).getTime() / 1000;
                    if (lastModTime <= lastAccessed) {
                        response.status(304);
                        sendBody = false;
                    }
                } catch (DateTimeParseException e) {
                    log.info("Ignoring cache check due to invalid If-Modified-Since header value: " + ims);
                }
            }

            String rh = request.headers().get("range");
            long maxAmountToSend = Long.MAX_VALUE;
            if (rh != null && totalSize != null && response.status() != 304) {
                try {
                    List<BytesRange> requestedRanges = BytesRange.parse(totalSize, rh);
                    if (requestedRanges.size() == 1) {
                        BytesRange range = requestedRanges.get(0);
                        boolean couldSkip = provider.skipIfPossible(range.from);
                        if (couldSkip) {
                            response.status(206);
                            maxAmountToSend = range.length();
                            response.headers().set(HeaderNames.CONTENT_LENGTH, maxAmountToSend);
                            response.headers().set(HeaderNames.CONTENT_RANGE, range.toString());
                        }
                    }
                } catch (IllegalArgumentException e) {
                    log.info("Ignoring range request due to invalid Range header value: " + rh);
                }
            }
            try {
                provider.sendTo(request, response, sendBody, maxAmountToSend);
            } catch (IOException e) {
                log.debug(request + " cancelled before full response sent to the client");
            }
        }

//        log.info(">> " + response + " - " + response.headers());
//        log.info("");
        return true;
    }

    private void addHeaders(MuResponse response, String fileName, Long fileSize, Date lastModified) {
        int ind = fileName.lastIndexOf('.');
        ResourceType type;
        if (ind == -1) {
            type = ResourceType.DEFAULT;
        } else {
            String extension = fileName.substring(ind + 1).toLowerCase();
            type = extensionToResourceType.getOrDefault(extension, ResourceType.DEFAULT);
        }
        response.contentType(type.mimeType);
        Headers headers = response.headers();
        headers.set(HeaderNames.ACCEPT_RANGES, HeaderValues.BYTES);
        headers.set(HeaderNames.VARY, "accept-encoding"); // to stop gzip caching issues
        if (fileSize != null) {
            headers.set(HeaderNames.CONTENT_LENGTH, fileSize);
        }
        if (lastModified != null) {
            headers.set(HeaderNames.LAST_MODIFIED, Mutils.toHttpDate(lastModified));
        }
        headers.add(type.headers);
    }

    /**
     * <p>Used to create a {@link ResourceHandler} for serving static files.</p>
     * <p>In order to serve from the filesystem during development, and from classpath and deploy time, see
     * {@link #fileOrClasspath(String, String)}</p>
     * @deprecated Use {@link ResourceHandlerBuilder}
     */
    @Deprecated
    public static class Builder implements MuHandlerBuilder<ResourceHandler> {
        private Map<String, ResourceType> extensionToResourceType = DEFAULT_EXTENSION_MAPPINGS;
        private String pathToServeFrom = "/";
        private String defaultFile = "index.html";
        private ResourceProviderFactory resourceProviderFactory;

        /**
         * Specify custom filename extension to mime-type mappings. By default {@link ResourceType#DEFAULT_EXTENSION_MAPPINGS}
         * are used.
         * @param extensionToResourceType The mappings to use.
         * @return The builder
         * @deprecated Use {@link ResourceHandlerBuilder}
         */
        @Deprecated
        public Builder withExtensionToResourceType(Map<String, ResourceType> extensionToResourceType) {
            this.extensionToResourceType = extensionToResourceType;
            return this;
        }

        /**
         * Specifies the path to serve the static from.
         * @param pathToServeFrom A path that static data should be served from. Defaults to <code>/</code>
         * @return The builder
         * @deprecated Use {@link ResourceHandlerBuilder}
         */
        @Deprecated
        public Builder withPathToServeFrom(String pathToServeFrom) {
            this.pathToServeFrom = pathToServeFrom;
            return this;
        }

        /**
         * Specifies the file to use when a request such as <code>/web/</code> is made. Defaults to <code>index.html</code>
         * @param defaultFile The default file to use when a directory is requested
         * @return The builder
         * @deprecated Use {@link ResourceHandlerBuilder}
         */
        @Deprecated
        public Builder withDefaultFile(String defaultFile) {
            this.defaultFile = defaultFile;
            return this;
        }

        Builder withResourceProviderFactory(ResourceProviderFactory resourceProviderFactory) {
            this.resourceProviderFactory = resourceProviderFactory;
            return this;
        }

        /**
         * Creates the handler
         * @return The built handler
         */
        public ResourceHandler build() {
            if (resourceProviderFactory == null) {
                throw new IllegalStateException("No resourceProviderFactory has been set");
            }
            return new ResourceHandler(resourceProviderFactory, pathToServeFrom, defaultFile, extensionToResourceType);
        }
    }

    /**
     * Creates a handler that serves files from the given directory.
     * @param directoryPath The directory.
     * @return A new builder.
     * @deprecated Use {@link ResourceHandlerBuilder#fileHandler(String)}
     */
    @Deprecated
    public static ResourceHandler.Builder fileHandler(String directoryPath) {
        return fileHandler(Paths.get(directoryPath));
    }

    /**
     * Creates a handler that serves files from the given directory.
     * @param baseDirectory The directory.
     * @return A new builder.
     * @deprecated Use {@link ResourceHandlerBuilder#fileHandler(File)}
     */
    @Deprecated
    public static ResourceHandler.Builder fileHandler(File baseDirectory) {
        return fileHandler(baseDirectory.toPath());
    }

    /**
     * Creates a handler that serves files from the given directory.
     * @param path The directory.
     * @return A new builder.
     * @deprecated Use {@link ResourceHandlerBuilder#fileHandler(Path)}
     */
    @Deprecated
    public static ResourceHandler.Builder fileHandler(Path path) {
        return new Builder().withResourceProviderFactory(ResourceProviderFactory.fileBased(path));
    }


    /**
     * Creates a handler that serves files from the classpath..
     * @param classpathRoot A classpath directory, such as <code>/web</code>
     * @return A new builder.
     * @deprecated Use {@link ResourceHandlerBuilder#classpathHandler(String)}
     */
    @Deprecated
    public static ResourceHandler.Builder classpathHandler(String classpathRoot) {
        return new Builder().withResourceProviderFactory(ResourceProviderFactory.classpathBased(classpathRoot));
    }

    /**
     * Creates a resource handler that serves from the file system if the directory exists; otherwise from the class path.
     * <p>
     * A common use case is for when you want to serve from the file path at development time (so you can update
     * files without restarting) but at deploy time resources are packaged in an uber jar.
     * @param fileRootIfExists A path to a directory holding static content, which may not exist, e.g. <code>src/main/resources/web</code>
     * @param classpathRoot A classpath path to a directory holding static content, e.g. <code>/web</code>
     * @return Returns a file-based resource handler builder or a classpath-based one.
     * @deprecated Use {@link ResourceHandlerBuilder#fileOrClasspath(String, String)}
     */
    @Deprecated
    public static ResourceHandler.Builder fileOrClasspath(String fileRootIfExists, String classpathRoot) {
        Path path = Paths.get(fileRootIfExists);
        if (Files.isDirectory(path)) {
            return fileHandler(path);
        } else {
            return classpathHandler(classpathRoot);
        }
    }

}
