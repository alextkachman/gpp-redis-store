/*
 * Copyright 2009-2010 MBTE Sweden AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mbte.redisstore

import java.util.logging.Logger;

import org.apache.catalina.Session
import org.apache.catalina.Store
import org.apache.catalina.util.CustomObjectInputStream
import org.apache.catalina.session.StandardSession
import org.apache.catalina.session.StoreBase

import redis.clients.jedis.Jedis
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import redis.clients.jedis.JedisPool

@Typed class GppRedisStore extends StoreBase implements Store {
    private static Log log = LogFactory.getLog(GppRedisStore);

    /**
     * Redis host
     */
    String host = "localhost"

    /**
     * Redis port. Defaults to 6379
     */
    int port = 6379

    /**
     * Redis Password
     */
    String password

    /**
     * Redis database
     */
    int database = 0

    private volatile JedisPool _pool

    private JedisPool getPool() {
        JedisPool res = _pool
        if(!res)
            synchronized(this) {
                res = _pool
                if(!res)
                    _pool = res = [host, port]
            }
        return res
    }

    Jedis borrowJedis() throws IOException {
        for(;;) {
            Jedis res = pool.resource
            if(res.connected)
                return res

            pool.returnBrokenResource(res)
        }
    }

    public void clear() throws IOException {
        for(;;) {
            Jedis jedis = pool.resource
            try {
                jedis.flushDB()
            }
            catch(e) {
                pool.returnBrokenResource(jedis)
                continue
            }
            pool.returnResource(jedis)
            return
        }
    }

    public int getSize() throws IOException {
        int size
        for(;;) {
            Jedis jedis = pool.resource
            try {
                size = jedis.dbSize()
            }
            catch(e) {
                pool.returnBrokenResource(jedis)
                continue
            }
            pool.returnResource(jedis)
            return size
        }
    }

    public String[] keys() throws IOException {
        String[] res
        for(;;) {
            Jedis jedis = pool.resource
            try {
                def keysList = jedis.keys ("*")
                res = keysList.toArray (new String [keysList.size ()])
            }
            catch(e) {
                pool.returnBrokenResource(jedis)
                continue
            }
            pool.returnResource(jedis)
            return res
        }
    }

    public Session load(String id) throws ClassNotFoundException, IOException {
        StandardSession session

        String res
        for(;;) {
            Jedis jedis = pool.resource
            try {
                res = jedis.get(id)
            }
            catch(e) {
                pool.returnBrokenResource(jedis)
                continue
            }
            pool.returnResource(jedis)
            break
        }

        def container = manager.container
        if (res) {
            try {
                def bis = new BufferedInputStream(new ByteArrayInputStream(deserializeHexBytes(res)))
                def classLoader = container?.loader?.classLoader
                def ois = classLoader ? new CustomObjectInputStream(bis, classLoader) : new ObjectInputStream(bis)
                session = manager.createEmptySession()
                session.readObjectData(ois)
                session.manager = manager
                log.info("Loaded session id $id")
            } catch (Exception ex) {
                log.error(ex.getMessage());
            }
        } else {
            log.warn("No persisted data object found");
        }

        session
    }

    public void remove(String id) throws IOException {
        for(;;) {
            Jedis jedis = pool.resource
            try {
                jedis.del(id)
                if(log.infoEnabled)
                    log.info("Removed session id $id")
            }
            catch(e) {
                pool.returnBrokenResource(jedis)
                continue
            }
            pool.returnResource(jedis)
            break
        }
    }

    public void save(Session session) throws IOException {
        ObjectOutputStream oos
        ByteArrayOutputStream bos

        bos = new ByteArrayOutputStream()
        oos = new ObjectOutputStream(new BufferedOutputStream(bos))

        ((StandardSession) session).writeObjectData(oos)
        oos.close()

        for(;;) {
            Jedis jedis = pool.resource
            try {
                jedis.set(session.idInternal, serializeHexBytes(bos.toByteArray()))
                log.info("Saved session with id " + session.getIdInternal())
            }
            catch(e) {
                pool.returnBrokenResource(jedis)
                continue
            }
            pool.returnResource(jedis)
            break
        }
    }

    public static String serializeBytes(byte[] a) {
        if (a == null) {
            return "null"
        }
        if (a.length == 0) {
            return ""
        }

        serializeHexBytes(a)
    }

    private static String serializeHexBytes(byte[] a) {
        def hexString = new StringBuilder(2 * a.length)
        for (i in 0..<a.length) {
            def b = a [i]
            hexString.append("0123456789ABCDEF".charAt((b & 0xF0) >> 4)).append("0123456789ABCDEF".charAt((b & 0x0F)));
        }
        hexString
    }

    private static byte[] deserializeHexBytes(String sbytes) {
        def bytes = new byte[sbytes.length() / 2];
        def i = 0, j = 0
        for (; i < bytes.length;) {
            char upper = Character.toLowerCase(sbytes.charAt(j++))
            char lower = Character.toLowerCase(sbytes.charAt(j++))
            bytes[i++] = (byte) ((Character.digit(upper, 16) << 4) | Character.digit(lower, 16))
        }
        bytes
    }
}