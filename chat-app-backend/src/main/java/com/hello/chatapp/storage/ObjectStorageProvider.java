package com.hello.chatapp.storage;

/**
 * Strategy interface for one object-storage provider implementation.
 */
public interface ObjectStorageProvider {

    ObjectStorageProviderDescriptor describe();

    ObjectStorageProviderType getType();
}
