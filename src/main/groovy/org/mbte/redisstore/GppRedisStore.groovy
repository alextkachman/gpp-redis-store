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
import java.util.logging.Level

@Typed class GppRedisStore extends StoreBase implements Store {
    private static Logger log = Logger.getLogger("RedisStore");

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

    private Jedis getJedis() throws IOException {

        def jedis = new Jedis(host, port);
        try {
            jedis.connect()
            jedis.select(database)
            return jedis
        } catch (UnknownHostException e) {
            log.severe("Unknown redis host")
            throw e
        } catch (IOException e) {
            log.severe("Unknown redis port")
            throw e
        }
    }

    private void closeJedis(Jedis jedis) throws IOException {
        jedis.quit();
        try {
            jedis.disconnect()
        } catch (IOException e) {
            log.severe(e.getMessage())
            throw e
        }
    }

    public void clear() throws IOException {
        def jedis = getJedis()
        jedis.flushDB()
        closeJedis(jedis)
    }

    public int getSize() throws IOException {
        def jedis = getJedis()
        def size = jedis.dbSize()
        closeJedis(jedis)
        size
    }

    public String[] keys() throws IOException {
        def jedis = getJedis ()
        def keysList = jedis.keys ("*")
        closeJedis (jedis);
        keysList.toArray (new String [keysList.size ()])
    }

    public Session load(String id) throws ClassNotFoundException, IOException {
        StandardSession session

        def container = manager.container

        def jedis = getJedis()
        def hash = jedis.get(id)
        closeJedis(jedis)

        if (hash) {
            try {
                def bis = new BufferedInputStream(new ByteArrayInputStream(deserializeHexBytes(hash)))
                def classLoader = container?.loader?.classLoader
                def ois = classLoader ? new CustomObjectInputStream(bis, classLoader) : new ObjectInputStream(bis)
                session = manager.createEmptySession()
                session.readObjectData(ois)
                session.setManager(manager)
                log.info("Loaded session id $id")
            } catch (Exception ex) {
                log.severe(ex.getMessage());
            }
        } else {
            log.warning("No persisted data object found");
        }
        return session
    }

    public void remove(String id) throws IOException {
        def jedis = getJedis()
        jedis.del(id)
        closeJedis(jedis)
        if(log.isLoggable(Level.INFO))
            log.info("Removed session id $id")
    }

    public void save(Session session) throws IOException {
        ObjectOutputStream oos
        ByteArrayOutputStream bos

        def hash = new HashMap<String, String>()
        bos = new ByteArrayOutputStream()
        oos = new ObjectOutputStream(new BufferedOutputStream(bos))

        ((StandardSession) session).writeObjectData(oos)
        oos.close()

        def jedis = getJedis();
        jedis.set(session.idInternal, serializeHexBytes(bos.toByteArray()))
        closeJedis(jedis)
        log.info("Saved session with id " + session.getIdInternal())
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