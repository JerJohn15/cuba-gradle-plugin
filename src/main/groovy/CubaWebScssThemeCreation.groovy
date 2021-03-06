/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import com.vaadin.sass.SassCompiler
import com.yahoo.platform.yui.compressor.CssCompressor
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import org.carrot2.labs.smartsprites.SmartSpritesParameters
import org.carrot2.labs.smartsprites.SpriteBuilder
import org.carrot2.labs.smartsprites.message.MessageLog
import org.carrot2.labs.smartsprites.message.PrintStreamMessageSink
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.*
import org.kohsuke.args4j.CmdLineParser

import java.nio.file.Files
import java.util.regex.Pattern

class CubaWebScssThemeCreation extends DefaultTask {

    // additional scss root from modules
    def includes = []

    // theme names to build
    def themes = []

    def scssDir = 'themes'
    def destDir = "${project.buildDir}/web/VAADIN/themes"

    def buildTimeStamp = ''
    def compress = true
    def sprites = true
    def cleanup = true

    def excludes = ['reindeer', 'chameleon', 'runo', 'liferay']
    def excludePaths = ['valo/fonts/lato',
                        'valo/fonts/lora',
                        'valo/fonts/roboto',
                        'valo/fonts/source-sans-pro',
                        'META-INF']

    def dirFilter = new FileFilter() {
        @Override
        boolean accept(File pathname) {
            return pathname.isDirectory() && !pathname.name.startsWith(".")
        }
    }

    CubaWebScssThemeCreation() {
        setDescription('Compile scss styles in theme')
        setGroup('Web resources')
    }

    @OutputDirectory
    def File getOutputDirectory() {
        return project.file(destDir)
    }

    @InputFiles @SkipWhenEmpty @Optional
    def FileCollection getSourceFiles() {
        def files = new ArrayList<File>()
        def themeDirs = themes.empty ?
                    project.file(scssDir).listFiles(dirFilter) :
                    themes.collect {new File(project.file(scssDir), it)}

        project.fileTree(scssDir, {
            for (def themeDir : themeDirs)
                include "${themeDir.name}/**"
            exclude '**/.*'
        }).each { def file ->
            files.add(file)
        }

        includes.each { def include ->
            File includeDir
            if (include instanceof String)
                includeDir = project.rootProject.file(include)
            else
                includeDir = include

            project.rootProject.fileTree(includeDir, {
                exclude '**/.*'
            }).each {def file ->
                files.add(file)
            }
        }

        return new SimpleFileCollection(files)
    }

    @TaskAction
    def buildThemes() {
        File themesTmp = project.file("${project.buildDir}/themes-tmp")
        if (themesTmp.exists())
            themesTmp.deleteDir()
        themesTmp.mkdir()

        File destinationDirectory
        if (destDir instanceof String)
            destinationDirectory = project.file(destDir)
        else
            destinationDirectory = destDir as File

        File stylesDirectory
        if (scssDir instanceof String)
            stylesDirectory = project.file(scssDir)
        else
            stylesDirectory = scssDir as File

        if (themes.empty) {
            project.logger.info("[CubaWebScssThemeCreation] scan directory '${stylesDirectory}' for themes")
            for (File themeDir : project.file(stylesDirectory).listFiles(dirFilter))
                themes.add(themeDir)
        }

        // unpack dependencies to destDir
        Configuration themesConf = project.configurations.findByName('themes')
        if (themesConf) {
            def themesResolvedArtifacts = themesConf.resolvedConfiguration.getResolvedArtifacts()
            themesResolvedArtifacts.each { ResolvedArtifact artifact ->
                project.logger.info("[CubaWebScssThemeCreation] unpack themes artifact ${artifact.name}")

                File tmpDir = Files.createTempDirectory('themes_' + artifact.name).toFile()
                try {
                    project.copy {
                        from project.zipTree(artifact.file.absolutePath)
                        into tmpDir
                    }

                    File artifactThemesRoot = tmpDir
                    // if used vaadin-style theme artifact
                    def vaadinThemesRoot = new File(tmpDir, 'VAADIN/themes')
                    if (vaadinThemesRoot.exists()) {
                        artifactThemesRoot = vaadinThemesRoot
                    }

                    artifactThemesRoot.eachDir { File dir ->
                        project.copy {
                            from dir
                            into new File(themesTmp, dir.name)
                        }
                    }
                } finally {
                    tmpDir.deleteDir()
                }
            }
        }

        // copy includes to build dir
        includes.each { File includeThemeDir ->
            project.logger.info("[CubaWebScssThemeCreation] copy includes from '${includeThemeDir.name}'")
            if (!includeThemeDir.exists())
                throw new FileNotFoundException("Could not found include dir ${includeThemeDir.absolutePath}")

            project.copy {
                from includeThemeDir
                into new File(themesTmp, includeThemeDir.name)
            }
        }

        // copy include resources
        includes.each { File includeThemeDir ->
            project.logger.info("[CubaWebScssThemeCreation] copy resources from '${includeThemeDir.name}'")
            if (!includeThemeDir.exists())
                throw new FileNotFoundException("Could not found include dir ${includeThemeDir.absolutePath}")

            def themeDestDir = new File(destinationDirectory, includeThemeDir.name)

            copyIncludeResources(includeThemeDir, themeDestDir)
        }

        File[] themeDirs = themesTmp.listFiles()
        if (themeDirs) {
            for (File themeSourceDir : themeDirs) {
                if (themeSourceDir.isDirectory()) {
                    project.logger.info("[CubaWebScssThemeCreation] copy resources from '${themeSourceDir.name}'")

                    def themeDestDir = new File(destinationDirectory, themeSourceDir.name)
                    copyIncludeResources(themeSourceDir, themeDestDir)
                }
            }
        }

        excludes.each { def themeName ->
            def themeDestDir = new File(destinationDirectory, themeName)
            project.logger.info("[CubaWebScssThemeCreation] excluded theme '$themeName'")

            FileUtils.deleteQuietly(themeDestDir)
        }

        excludePaths.each { def path ->
            def pathFile = new File(destinationDirectory, path)
            project.logger.info("[CubaWebScssThemeCreation] excluded path '$path'")

            FileUtils.deleteQuietly(pathFile)
        }

        themes.each { def themeDir ->
            if (themeDir instanceof String)
                themeDir = new File(stylesDirectory, themeDir)

            def themeBuildDir = new File(themesTmp, themeDir.name)
            def themeDestDir = new File(destinationDirectory, themeDir.name)
            if (!themeDestDir.exists())
                themeDestDir.mkdir()

            project.logger.info("[CubaWebScssThemeCreation] copy theme '${themeDir.name}' to build directory")
            // copy theme to build directory
            project.copy {
                from themeDir
                into themeBuildDir
                exclude {
                    it.file.name.startsWith('.')
                }
            }

            project.logger.info("[CubaWebScssThemeCreation] copy theme resources for '${themeDir.name}'")
            // copy resources from themeBuildDir, override may be used
            project.copy {
                from themeBuildDir
                into themeDestDir
                exclude {
                    it.file.name.startsWith('.') || it.file.name.endsWith('.scss')
                }
            }

            project.logger.info("[CubaWebScssThemeCreation] compile theme '${themeDir.name}'")

            def scssFilePath = project.file("${themeBuildDir}/styles.scss").absolutePath
            def cssFilePath = project.file("${themeDestDir}/styles.css").absolutePath

            SassCompiler.main(scssFilePath, cssFilePath)

            if (sprites) {
                project.logger.info("[CubaWebScssThemeCreation] compile sprites for theme '${themeDir.name}'")

                def compiledSpritesDir = new File(themeDestDir, 'compiled')
                if (!compiledSpritesDir.exists())
                    compiledSpritesDir.mkdir()

                def processedFile = new File(themeDestDir, 'styles-sprite.css')
                def cssFile = new File(cssFilePath)

                // process
                final SmartSpritesParameters parameters = new SmartSpritesParameters()
                final CmdLineParser parser = new CmdLineParser(parameters)

                parser.parseArgument('--root-dir-path', themeDestDir.absolutePath)

                // Get parameters form system properties
                final MessageLog messageLog = new MessageLog(new PrintStreamMessageSink(System.out, parameters.getLogLevel()))
                new SpriteBuilder(parameters, messageLog).buildSprites()

                def dirsToDelete = []
                // remove sprites directories
                themeDestDir.eachDirRecurse { if ('sprites'.equals(it.name)) dirsToDelete.add(it) }
                dirsToDelete.each { it.deleteDir() }

                // replace file
                if (processedFile.exists()) {
                    cssFile.delete()
                    processedFile.renameTo(cssFile)
                }
            }

            if (compress) {
                project.logger.info("[CubaWebScssThemeCreation] compress theme '${themeDir.name}'")

                def compressedFile = new File(cssFilePath + '.compressed')
                def cssFile = new File(cssFilePath)

                def cssReader = new FileReader(cssFile)
                BufferedWriter out = new BufferedWriter(new FileWriter(compressedFile))

                CssCompressor compressor = new CssCompressor(cssReader)
                compressor.compress(out, 0)

                out.close()
                cssReader.close()

                if (compressedFile.exists()) {
                    cssFile.delete()
                    compressedFile.renameTo(cssFile)
                }
            }

            if (cleanup) {
                // remove empty directories
                recursiveVisitDir(themeDestDir, { File f ->
                    boolean isEmpty = f.list().length == 0
                    if (isEmpty) {
                        project.logger.info("[CubaWebScssThemeCreation] remove empty dir ${themeDestDir.toPath().relativize(f.toPath())} in '${themeDir.name}'")
                        f.deleteDir()
                    }
                })
            }

            if (StringUtils.isEmpty(buildTimeStamp) && project.ext.has('webResourcesTs')) {
                // detect version automatically
                buildTimeStamp = project.ext.get('webResourcesTs')
            }

            // update build timestamp for urls
            if (StringUtils.isNotEmpty(buildTimeStamp)) {
                project.logger.info("[CubaWebScssThemeCreation] add build timestamp to '${themeDir.name}'")
                // read
                def cssFile = new File(cssFilePath)
                def versionedFile = new File(cssFilePath + ".versioned")
                def cssContent = cssFile.getText("UTF-8")
                // find
                def inspector = new CssUrlInspector()
                // replace
                for (String url : inspector.getUrls(cssContent)) {
                    cssContent = cssContent.replace(url, url + '?v=' + buildTimeStamp)
                }
                // write
                versionedFile.write(cssContent, "UTF-8")
                cssFile.delete()
                versionedFile.renameTo(cssFile)
            }

            project.logger.info("[CubaWebScssThemeCreation] successfully compiled theme '${themeDir.name}'")
        }

        if (cleanup) {
            themesTmp.deleteDir()
        }
    }

    def copyIncludeResources(File themeSourceDir, File themeDestDir) {
        project.copy {
            from themeSourceDir
            into themeDestDir
            exclude {
                it.file.name.startsWith('.') || 'favicon.ico' == it.file.name || it.file.name.endsWith('.scss') || it.file.name.endsWith('.css')
            }
        }

        if (cleanup) {
            // remove empty directories
            recursiveVisitDir(themeDestDir, { File f ->
                boolean isEmpty = f.list().length == 0
                if (isEmpty) {
                    project.logger.info("[CubaWebScssThemeCreation] remove empty dir ${themeDestDir.toPath().relativize(f.toPath())} in '${themeDestDir.name}'")
                    f.deleteDir()
                }
            })
        }
    }

    def recursiveVisitDir(File dir, Closure apply) {
        for (def f : dir.listFiles()) {
            if (f.exists() && f.isDirectory()) {
                recursiveVisitDir(f, apply)
                apply(f)
            }
        }
    }

    static class CssUrlInspector {
        private static final Pattern CSS_URL_PATTERN =
            Pattern.compile('url\\([\\s]*[\'|\"]?([^\\)\\ \'\"]*)[\'|\"]?[\\s]*\\)')

        @SuppressWarnings("GrMethodMayBeStatic")
        public Set<String> getUrls(String cssContent) {
            // replace comments
            cssContent = cssContent.replaceAll('/\\*.*\\*/', '')

            def matcher = CSS_URL_PATTERN.matcher(cssContent)
            def urls = new HashSet<String>()
            while (matcher.find()) {
                urls.add(matcher.group(1))
            }
            return urls
        }
    }
}