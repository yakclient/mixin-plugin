module yakclient.mixin.plugin {
    requires kotlin.stdlib;
    requires kotlin.reflect;
    requires yakclient.boot;
    requires java.logging;
    requires yakclient.common.util;
    requires yakclient.archives.mixin;
    requires yakclient.archives;
    requires org.objectweb.asm;

    exports net.yakclient.plugins.mixin;
}