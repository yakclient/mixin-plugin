package net.yakclient.plugins.mixin

import net.yakclient.archives.ArchiveReference
import net.yakclient.archives.Archives
import net.yakclient.archives.mixin.Mixins
import net.yakclient.archives.transform.ProxiedTransformer
import net.yakclient.archives.transform.TransformerConfig
import net.yakclient.boot.Boot
import net.yakclient.boot.event.ApplicationLaunchEvent
import net.yakclient.boot.event.ApplicationLoadEvent
import net.yakclient.boot.plugin.BootPlugin
import net.yakclient.common.util.immutableLateInit
import net.yakclient.common.util.readInputStream
import org.objectweb.asm.ClassReader
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger

public class MixinPlugin : BootPlugin {
    private val logger = Logger.getLogger(this::class.simpleName)

    override fun onLoad() {
        logger.log(Level.INFO, "Mixin plugin loading")
        isLoaded = true


        Boot.eventManager.subscribe(ApplicationLoadEvent::class) { (ref) ->
            val reader by ref::reader

            val properties = reader[APPLICATION_COMPLIANCE_FILE]?.let {
                val properties = Properties()
                properties.load(it.resource.open())
                properties
            } ?: run {
                logger.log(Level.INFO, "Mixin plugin not loading, current app is not mixin compliant.")
                return@subscribe
            }

            mixinAccessClassname = properties.getProperty(COMPLIANCE_FILE_ACCESS_PROPERTY_NAME)
        }

        Boot.eventManager.subscribe(ApplicationLaunchEvent::class) { (handle, _) ->
            if (!isAppCompliant) return@subscribe

            val accessClass = handle.classloader.loadClass(mixinAccessClassname)

            val constructor = runCatching { accessClass.getConstructor() }.getOrNull()
                ?: throw IllegalStateException("Class: '$mixinAccessClassname' in Booted App has no empty arg constructor!")
            val type = runCatching { constructor.newInstance() }.getOrNull()
                ?: throw IllegalStateException("Failed to instantiate type: '$mixinAccessClassname' in Booted App.")

            mixinAccess = (type as? MixinAccess)
                ?: throw IllegalStateException("Type: '$mixinAccessClassname' must implement '${MixinAccess::class.qualifiedName}'.")

            writeAll()
        }
    }

    override fun onUnload() {
        if (isLoaded && isAppCompliant) logger.log(Level.INFO, "Mixin plugin Unloading")
        isLoaded = false
    }

    public data class MixinMetadata(
        val self: String,
        val metadata: Mixins.InjectionMetaData,
    )

    public companion object {
        private const val APPLICATION_COMPLIANCE_FILE = "/META-INF/mixins/compliance.properties"
        private const val COMPLIANCE_FILE_ACCESS_PROPERTY_NAME = "mixin-access-classname"

        private var archive: ArchiveReference by immutableLateInit()
        private var mixinAccess: MixinAccess by immutableLateInit()
        public var isLoaded: Boolean = false
            private set
        private var mixinAccessClassname: String? = null
        public val isAppCompliant: Boolean
            get() = mixinAccessClassname != null
        private val updatedMixins: MutableSet<String> = HashSet()
        private val mixins: MutableMap<String, MutableList<MixinMetadata>> = HashMap()

        private fun checkLoaded() {
            if (!isLoaded) {
                if (isAppCompliant) throw IllegalStateException("Cannot access mixin plugin at this point, onLoad has not been called yet.")
                else throw IllegalStateException("Cannot access mixin plugin with the given app configuration, it contains no mixin compliance file.")
            }
        }

        public fun registerMixin(to: String, metadata: MixinMetadata) {
            checkLoaded()
            check(archive.reader.contains(to)) { "Class '$to' does not exist." }

            val injects = mixins[to] ?: ArrayList<MixinMetadata>().also { mixins[to] = it }
            injects.add(metadata)

            updatedMixins.add(to)
        }

        public fun writeAll() {
            checkLoaded()

            val mixins = mixins.filter { updatedMixins.contains(it.key) }.entries

            val toWrite = mixins.map { (to, all) ->
                val toProcess = HashMap<String, MutableList<Mixins.InjectionMetaData>>()

                all.forEach { metadata ->
                    val allToProcess =
                        if (toProcess.contains(metadata.self)) ArrayList<Mixins.InjectionMetaData>().also {
                            toProcess[metadata.self] = it
                        } else ArrayList()
                    allToProcess.add(metadata.metadata)
                }

                toProcess.map {
                    Mixins.mixinOf(to, it.key, it.value)
                }.reduce { acc: TransformerConfig, t: TransformerConfig.MutableTransformerConfiguration ->
                    TransformerConfig(
                        ProxiedTransformer(listOf(acc.ct, t.ct)),
                        ProxiedTransformer(listOf(acc.mt, t.mt)),
                        ProxiedTransformer(listOf(acc.ft, t.ft)),
                    )
                } to to
            }

            toWrite.forEach { (config, to) ->
                val bytes = mixinAccess.read(to)
                    ?: throw IllegalArgumentException("Failed to inject into class '$to' because it does not exist!")
                mixinAccess.write(to, Archives.resolve(ClassReader(bytes), config))
            }
        }
    }
}