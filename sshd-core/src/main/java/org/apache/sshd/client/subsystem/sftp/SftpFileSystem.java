/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.subsystem.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.FactoryManagerUtils;
import org.apache.sshd.common.file.util.BaseFileSystem;
import org.apache.sshd.common.file.util.ImmutableList;
import org.apache.sshd.common.util.GenericUtils;

public class SftpFileSystem extends BaseFileSystem<SftpPath> {
    public static final String POOL_SIZE_PROP = "sftp-fs-pool-size";
        public static final int DEFAULT_POOL_SIZE = 8;

    public static final Set<String> SUPPORTED_VIEWS =
            Collections.unmodifiableSet(
                    GenericUtils.asSortedSet(String.CASE_INSENSITIVE_ORDER,
                            Arrays.asList(
                                "basic", "posix", "owner"
                            )));

    private final String id;
    private final ClientSession session;
    private final Queue<SftpClient> pool;
    private final ThreadLocal<Wrapper> wrappers = new ThreadLocal<>();
    private SftpPath defaultDir;
    private int readBufferSize = SftpClient.DEFAULT_READ_BUFFER_SIZE;
    private int writeBufferSize = SftpClient.DEFAULT_WRITE_BUFFER_SIZE;
    private final List<FileStore> stores;

    public SftpFileSystem(SftpFileSystemProvider provider, String id, ClientSession session) throws IOException {
        super(provider);
        this.id = id;
        this.session = session;
        this.stores = Collections.unmodifiableList(Collections.<FileStore>singletonList(new SftpFileStore(id, this)));
        this.pool = new LinkedBlockingQueue<>(FactoryManagerUtils.getIntProperty(session, POOL_SIZE_PROP, DEFAULT_POOL_SIZE));
        try (SftpClient client = getClient()) {
            defaultDir = getPath(client.canonicalPath("."));
        }
    }

    public final String getId() {
        return id;
    }

    @Override
    public SftpFileSystemProvider provider() {
        return (SftpFileSystemProvider) super.provider();
    }

    @Override   // NOTE: co-variant return
    public List<FileStore> getFileStores() {
        return this.stores;
    }

    public int getReadBufferSize() {
        return readBufferSize;
    }

    public void setReadBufferSize(int size) {
        if (size < SftpClient.MIN_READ_BUFFER_SIZE) {
            throw new IllegalArgumentException("Insufficient read buffer size: " + size + ", min.=" + SftpClient.MIN_READ_BUFFER_SIZE);
        }

        readBufferSize = size;
    }

    public int getWriteBufferSize() {
        return writeBufferSize;
    }

    public void setWriteBufferSize(int size) {
        if (size < SftpClient.MIN_WRITE_BUFFER_SIZE) {
            throw new IllegalArgumentException("Insufficient write buffer size: " + size + ", min.=" + SftpClient.MIN_WRITE_BUFFER_SIZE);
        }

        writeBufferSize = size;
    }

    @Override
    protected SftpPath create(String root, ImmutableList<String> names) {
        return new SftpPath(this, root, names);
    }

    public ClientSession getSession() {
        return session;
    }

    @SuppressWarnings("synthetic-access")
    public SftpClient getClient() throws IOException {
        Wrapper wrapper = wrappers.get();
        if (wrapper == null) {
            while (wrapper == null) {
                SftpClient client = pool.poll();
                if (client == null) {
                    client = session.createSftpClient();
                }
                if (!client.isClosing()) {
                    wrapper = new Wrapper(client, getReadBufferSize(), getWriteBufferSize());
                }
            }
            wrappers.set(wrapper);
        } else {
            wrapper.increment();
        }
        return wrapper;
    }

    @Override
    public void close() throws IOException {
        if (isOpen()) {
            SftpFileSystemProvider provider = provider();
            String fsId = getId();
            SftpFileSystem fs = provider.removeFileSystem(fsId);
            session.close(true);
            
            if ((fs != null) && (fs != this)) {
                throw new FileSystemException(fsId, fsId, "Mismatched FS instance for id=" + fsId);
            }
        }
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return SUPPORTED_VIEWS;
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        return new DefaultUserPrincipalLookupService();
    }

    @Override
    public SftpPath getDefaultDir() {
        return defaultDir;
    }

    private class Wrapper extends AbstractSftpClient {

        private final SftpClient delegate;
        private final AtomicInteger count = new AtomicInteger(1);
        private final int readSize, writeSize;

        private Wrapper(SftpClient delegate, int readSize, int writeSize) {
            this.delegate = delegate;
            this.readSize = readSize;
            this.writeSize = writeSize;
        }

        @Override
        public int getVersion() {
            return delegate.getVersion();
        }

        @Override
        public boolean isClosing() {
            return false;
        }

        @Override
        public boolean isOpen() {
            if (count.get() > 0) {
                return true;
            } else {
                return false;   // debug breakpoint
            }
        }

        @SuppressWarnings("synthetic-access")
        @Override
        public void close() throws IOException {
            if (count.decrementAndGet() <= 0) {
                if (!pool.offer(delegate)) {
                    delegate.close();
                }
                wrappers.set(null);
            }
        }

        public void increment() {
            count.incrementAndGet();
        }

        @Override
        public CloseableHandle open(String path, Collection<OpenMode> options) throws IOException {
            if (!isOpen()) {
                throw new IOException("open(" + path + ")[" + options + "] client is closed");
            }
            return delegate.open(path, options);
        }

        @Override
        public void close(Handle handle) throws IOException {
            if (!isOpen()) {
                throw new IOException("close(" + handle + ") client is closed");
            }
            delegate.close(handle);
        }

        @Override
        public void remove(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("remove(" + path + ") client is closed");
            }
            delegate.remove(path);
        }

        @Override
        public void rename(String oldPath, String newPath, Collection<CopyMode> options) throws IOException {
            if (!isOpen()) {
                throw new IOException("rename(" + oldPath + " => " + newPath + ")[" + options + "] client is closed");
            }
            delegate.rename(oldPath, newPath, options);
        }

        @Override
        public int read(Handle handle, long fileOffset, byte[] dst, int dstOffset, int len) throws IOException {
            if (!isOpen()) {
                throw new IOException("read(" + handle + "/" + fileOffset + ")[" + dstOffset + "/" + len + "] client is closed");
            }
            return delegate.read(handle, fileOffset, dst, dstOffset, len);
        }

        @Override
        public void write(Handle handle, long fileOffset, byte[] src, int srcOffset, int len) throws IOException {
            if (!isOpen()) {
                throw new IOException("write(" + handle + "/" + fileOffset + ")[" + srcOffset + "/" + len + "] client is closed");
            }
            delegate.write(handle, fileOffset, src, srcOffset, len);
        }

        @Override
        public void mkdir(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("mkdir(" + path + ") client is closed");
            }
            delegate.mkdir(path);
        }

        @Override
        public void rmdir(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("rmdir(" + path + ") client is closed");
            }
            delegate.rmdir(path);
        }

        @Override
        public CloseableHandle openDir(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("openDir(" + path + ") client is closed");
            }
            return delegate.openDir(path);
        }

        @Override
        public DirEntry[] readDir(Handle handle) throws IOException {
            if (!isOpen()) {
                throw new IOException("readDir(" + handle + ") client is closed");
            }
            return delegate.readDir(handle);
        }

        @Override
        public String canonicalPath(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("canonicalPath(" + path + ") client is closed");
            }
            return delegate.canonicalPath(path);
        }

        @Override
        public Attributes stat(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("stat(" + path + ") client is closed");
            }
            return delegate.stat(path);
        }

        @Override
        public Attributes lstat(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("lstat(" + path + ") client is closed");
            }
            return delegate.lstat(path);
        }

        @Override
        public Attributes stat(Handle handle) throws IOException {
            if (!isOpen()) {
                throw new IOException("stat(" + handle + ") client is closed");
            }
            return delegate.stat(handle);
        }

        @Override
        public void setStat(String path, Attributes attributes) throws IOException {
            if (!isOpen()) {
                throw new IOException("setStat(" + path + ")[" + attributes + "] client is closed");
            }
            delegate.setStat(path, attributes);
        }

        @Override
        public void setStat(Handle handle, Attributes attributes) throws IOException {
            if (!isOpen()) {
                throw new IOException("setStat(" + handle + ")[" + attributes + "] client is closed");
            }
            delegate.setStat(handle, attributes);
        }

        @Override
        public String readLink(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("readLink(" + path + ") client is closed");
            }
            return delegate.readLink(path);
        }

        @Override
        public void symLink(String linkPath, String targetPath) throws IOException {
            if (!isOpen()) {
                throw new IOException("symLink(" + linkPath + " => " + targetPath + ") client is closed");
            }
            delegate.symLink(linkPath, targetPath);
        }

        @Override
        public Iterable<DirEntry> readDir(String path) throws IOException {
            if (!isOpen()) {
                throw new IOException("readDir(" + path + ") client is closed");
            }
            return delegate.readDir(path);
        }

        @Override
        public InputStream read(String path) throws IOException {
            return read(path, readSize);
        }

        @Override
        public InputStream read(String path, OpenMode... mode) throws IOException {
            return read(path, readSize, mode);
        }

        @Override
        public InputStream read(String path, Collection<OpenMode> mode) throws IOException {
            return read(path, readSize, mode);
        }

        @Override
        public InputStream read(String path, int bufferSize, Collection<OpenMode> mode) throws IOException {
            if (!isOpen()) {
                throw new IOException("read(" + path + ")[" + mode + "] size=" + bufferSize + ": client is closed");
            }
            return delegate.read(path, bufferSize, mode);
        }

        @Override
        public OutputStream write(String path) throws IOException {
            return write(path, writeSize);
        }

        @Override
        public OutputStream write(String path, OpenMode... mode) throws IOException {
            return write(path, writeSize, mode);
        }

        @Override
        public OutputStream write(String path, Collection<OpenMode> mode) throws IOException {
            return write(path, writeSize, mode);
        }

        @Override
        public OutputStream write(String path, int bufferSize, Collection<OpenMode> mode) throws IOException {
            if (!isOpen()) {
                throw new IOException("write(" + path + ")[" + mode + "] size=" + bufferSize + ": client is closed");
            }
            return delegate.write(path, bufferSize, mode);
        }

        @Override
        public void link(String linkPath, String targetPath, boolean symbolic) throws IOException {
            if (!isOpen()) {
                throw new IOException("link(" + linkPath + " => " + targetPath + "] symbolic=" + symbolic + ": client is closed");
            }
            delegate.link(linkPath, targetPath, symbolic);
        }

        @Override
        public void lock(Handle handle, long offset, long length, int mask) throws IOException {
            if (!isOpen()) {
                throw new IOException("lock(" + handle + ")[offset=" + offset + ", length=" + length + ", mask=0x" + Integer.toHexString(mask) + "] client is closed");
            }
            delegate.lock(handle, offset, length, mask);
        }

        @Override
        public void unlock(Handle handle, long offset, long length) throws IOException {
            if (!isOpen()) {
                throw new IOException("unlock" + handle + ")[offset=" + offset + ", length=" + length + "] client is closed");
            }
            delegate.unlock(handle, offset, length);
        }
    }

    protected static class DefaultUserPrincipalLookupService extends UserPrincipalLookupService {

        @Override
        public UserPrincipal lookupPrincipalByName(String name) throws IOException {
            return new DefaultUserPrincipal(name);
        }

        @Override
        public GroupPrincipal lookupPrincipalByGroupName(String group) throws IOException {
            return new DefaultGroupPrincipal(group);
        }
    }

    protected static class DefaultUserPrincipal implements UserPrincipal {

        private final String name;

        public DefaultUserPrincipal(String name) {
            if (name == null) {
                throw new IllegalArgumentException("name is null");
            }
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DefaultUserPrincipal that = (DefaultUserPrincipal) o;
            return name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    protected static class DefaultGroupPrincipal extends DefaultUserPrincipal implements GroupPrincipal {

        public DefaultGroupPrincipal(String name) {
            super(name);
        }

    }

}
