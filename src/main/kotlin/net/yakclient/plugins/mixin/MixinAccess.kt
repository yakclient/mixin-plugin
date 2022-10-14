package net.yakclient.plugins.mixin

public interface MixinAccess {
    public fun read(name: String) : ByteArray?

    public fun write(name: String, bytes: ByteArray)
}