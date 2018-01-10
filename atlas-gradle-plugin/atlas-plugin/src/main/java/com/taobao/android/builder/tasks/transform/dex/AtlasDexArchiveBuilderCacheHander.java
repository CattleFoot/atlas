package com.taobao.android.builder.tasks.transform.dex;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.gradle.internal.BuildCacheUtils;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.pipeline.OriginalStream;
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransform;
import com.android.build.gradle.internal.transforms.PreDexTransform;
import com.android.builder.core.DexOptions;
import com.android.builder.dexing.DexerTool;
import com.android.builder.utils.FileCache;
import com.android.dx.Version;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.base.Verify;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * @author lilong
 * @create 2017-12-08 上午3:47
 */

public class AtlasDexArchiveBuilderCacheHander {
    private static final LoggerWrapper logger =
            LoggerWrapper.getLogger(AtlasDexArchiveBuilderCacheHander.class);

    // Increase this if we might have generated broken cache entries to invalidate them.
    private static final int CACHE_KEY_VERSION = 4;

    @Nullable
    private final FileCache userLevelCache;
    @NonNull
    private final DexOptions dexOptions;
    private final int minSdkVersion;
    private final boolean isDebuggable;
    @NonNull
    private final DexerTool dexer;

    AtlasDexArchiveBuilderCacheHander(
            @Nullable FileCache userLevelCache,
            @NonNull DexOptions dexOptions,
            int minSdkVersion,
            boolean isDebuggable,
            @NonNull DexerTool dexer) {
        this.userLevelCache = userLevelCache;
        this.dexOptions = dexOptions;
        this.minSdkVersion = minSdkVersion;
        this.isDebuggable = isDebuggable;
        this.dexer = dexer;
    }

    @Nullable
    File getCachedVersionIfPresent(JarInput input) throws IOException {
        FileCache cache =
                getBuildCache(
                        input.getFile(), isExternalLib(input), userLevelCache);

        if (cache == null) {
            return null;
        }

        FileCache.Inputs buildCacheInputs =
                getBuildCacheInputs(
                        input.getFile(), dexOptions, dexer, minSdkVersion, isDebuggable);
        return cache.cacheEntryExists(buildCacheInputs)
                ? cache.getFileInCache(buildCacheInputs)
                : null;
    }

    /**
     * Returns if the qualified content is an external jar.
     */
    private static boolean isExternalLib(@NonNull QualifiedContent content) {
        return content.getFile().isFile()
                && content.getScopes()
                .equals(Collections.singleton(QualifiedContent.Scope.EXTERNAL_LIBRARIES))
                && content.getContentTypes()
                .equals(Collections.singleton(QualifiedContent.DefaultContentType.CLASSES))
                && !content.getName().startsWith(OriginalStream.LOCAL_JAR_GROUPID);
    }

    /**
     * Input parameters to be provided by the client when using {@link FileCache}.
     * <p>
     * <p>The clients of {@link FileCache} need to exhaustively specify all the inputs that affect
     * the creation of an output file/directory. This enum class lists the input parameters that are
     * used in {@link DexArchiveBuilderCacheHandler}.
     */
    private enum FileCacheInputParams {

        /**
         * The input file.
         */
        FILE,

        /**
         * Dx version used to create the dex archive.
         */
        DX_VERSION,

        /**
         * Whether jumbo mode is enabled.
         */
        JUMBO_MODE,

        /**
         * Whether optimize is enabled.
         */
        OPTIMIZE,

        /**
         * Tool used to produce the dex archive.
         */
        DEXER_TOOL,

        /**
         * Version of the cache key.
         */
        CACHE_KEY_VERSION,

        /**
         * Min sdk version used to generate dex.
         */
        MIN_SDK_VERSION,

        /**
         * If generate dex is debuggable.
         */
        IS_DEBUGGABLE,
    }

    /**
     * Returns a {@link FileCache.Inputs} object computed from the given parameters for the
     * predex-library task to use the build cache.
     */
    @NonNull
    public static FileCache.Inputs getBuildCacheInputs(
            @NonNull File inputFile,
            @NonNull DexOptions dexOptions,
            @NonNull DexerTool dexerTool,
            int minSdkVersion,
            boolean isDebuggable)
            throws IOException {
        // To use the cache, we need to specify all the inputs that affect the outcome of a pre-dex
        // (see DxDexKey for an exhaustive list of these inputs)
        FileCache.Inputs.Builder buildCacheInputs =
                new FileCache.Inputs.Builder(FileCache.Command.PREDEX_LIBRARY_TO_DEX_ARCHIVE);

        buildCacheInputs
                .putFile(
                        FileCacheInputParams.FILE.name(),
                        inputFile,
                        FileCache.FileProperties.PATH_HASH)
                .putString(FileCacheInputParams.DX_VERSION.name(), Version.VERSION)
                .putBoolean(FileCacheInputParams.JUMBO_MODE.name(), isJumboModeEnabledForDx())
                .putBoolean(
                        FileCacheInputParams.OPTIMIZE.name(),
                        !dexOptions.getAdditionalParameters().contains("--no-optimize"))
                .putString(FileCacheInputParams.DEXER_TOOL.name(), dexerTool.name())
                .putLong(FileCacheInputParams.CACHE_KEY_VERSION.name(), CACHE_KEY_VERSION)
                .putLong(FileCacheInputParams.MIN_SDK_VERSION.name(), minSdkVersion)
                .putBoolean(FileCacheInputParams.IS_DEBUGGABLE.name(), isDebuggable);

        return buildCacheInputs.build();
    }

    /**
     * Jumbo mode is always enabled for dex archives - see http://b.android.com/321744
     */
    static boolean isJumboModeEnabledForDx() {
        return true;
    }


    static FileCache getBuildCache(
            @NonNull File inputFile, boolean isExternalLib, @Nullable FileCache buildCache) {
        // We use the build cache only when it is enabled and the input file is a (non-snapshot)
        // external-library jar file
        if (buildCache == null || !isExternalLib) {
            return null;
        }
        // After the check above, here the build cache should be enabled and the input file is an
        // external-library jar file. We now check whether it is a snapshot version or not (to
        // address http://b.android.com/228623).
        // Note that the current check is based on the file path; if later on there is a more
        // reliable way to verify whether an input file is a snapshot, we should replace this check
        // with that.
        if (inputFile.getPath().contains("-SNAPSHOT")) {
            return null;
        } else {
            return buildCache;
        }
    }
}