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

@Typed package org.mbte.redisstore

import org.apache.catalina.startup.Tomcat
import org.apache.catalina.session.PersistentManager
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.CountDownLatch
import org.mbte.gretty.httpclient.GrettyClient
import org.mbte.gretty.httpserver.GrettyHttpRequest
import org.jboss.netty.handler.codec.http.HttpMethod
import org.jboss.netty.handler.codec.http.HttpVersion
import org.jboss.netty.handler.codec.http.CookieDecoder
import org.jboss.netty.handler.codec.http.CookieEncoder
import org.jboss.netty.handler.codec.http.HttpHeaders
import org.apache.catalina.core.StandardContext
import org.apache.catalina.valves.PersistentValve

class TomcatTest extends GroovyTestCase {
    void testHelloWorld () {
        TomcatForTest tomcat = [port:8082, baseDir:"."]
        tomcat.start(HelloWorldServlet)

        try {
            GrettyClient client = [new InetSocketAddress('localhost', 8082)]
            client.connect().await()

            GrettyHttpRequest req = [HttpVersion.HTTP_1_0, HttpMethod.GET, '/']
            req.setHeader HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE

            def resp = client.request(req).get()
            println resp

            def cb = resp.content
            def msg = new String(cb.array(), cb.arrayOffset(), cb.readableBytes(), "UTF-8")
            assert msg == 'Hello, World! 1'
            println msg
            def cookie1 = new CookieDecoder().decode(resp.getHeader('Set-Cookie')).asList()[0]
            println "$cookie1.name $cookie1.value"

            req = [HttpVersion.HTTP_1_0, HttpMethod.GET, '/']
            req.setHeader HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE

            resp = client.request(req).get()
            println resp

            cb = resp.content
            msg = new String(cb.array(), cb.arrayOffset(), cb.readableBytes(), "UTF-8")
            assert msg == 'Hello, World! 1'
            println msg
            def cookie2 = new CookieDecoder().decode(resp.getHeader(HttpHeaders.Names.SET_COOKIE)).asList()[0]
            assert cookie1 != cookie2


            req = [HttpVersion.HTTP_1_0, HttpMethod.GET, "/"]
            req.setHeader HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE

            def encoder = new CookieEncoder(false)
            encoder.addCookie(cookie2.name, cookie2.value)
            req.setHeader(HttpHeaders.Names.COOKIE, encoder.encode())

            resp = client.request(req).get()
            println resp

            def setCookieHeader = resp.getHeader(HttpHeaders.Names.SET_COOKIE)
            assert setCookieHeader == null

            cb = resp.content
            msg = new String(cb.array(), cb.arrayOffset(), cb.readableBytes(), "UTF-8")
            assert msg == 'Hello, World! 2'
            println msg

            tomcat.stop()
            tomcat = [port:8082, baseDir:"."]
            tomcat.start(HelloWorldServlet)

            client = [new InetSocketAddress('localhost', 8082)]
            client.connect().await()

            req = [HttpVersion.HTTP_1_0, HttpMethod.GET, "/"]
            req.setHeader HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE

            encoder = new CookieEncoder(false)
            encoder.addCookie(cookie2.name, cookie2.value)
            req.setHeader(HttpHeaders.Names.COOKIE, encoder.encode())

            resp = client.request(req).get()
            println resp

            setCookieHeader = resp.getHeader(HttpHeaders.Names.SET_COOKIE)
            assert setCookieHeader == null

            cb = resp.content
            msg = new String(cb.array(), cb.arrayOffset(), cb.readableBytes(), "UTF-8")
            assert msg == 'Hello, World! 3'
            println msg
        }
        finally {
            tomcat.stop ()
        }
    }
}

class TomcatForTest extends Tomcat {
    void start(Class<HttpServlet> servletClass) {
        StandardContext context = addContext("/", "")

        addServlet context, "TestServlet", servletClass.name
        context.addServletMapping "/", "TestServlet"

        context.distributable = true
        context.manager = new PersistentManager(store: new GppRedisStore())

        context.addValve(new PersistentValve())

        CountDownLatch cdlStart = [1]
        server.addLifecycleListener { event ->
            if(event.type == "start")
                cdlStart.countDown()
        }

        start()
        cdlStart.await()
    }

    void stop () {
        server.stop()
        server.await()
        server.destroy()
    }
}

class HelloWorldServlet extends HttpServlet {
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        def session = req.getSession()
        Integer invoke = session.getAttribute('invoke') ?: 1
        resp.outputStream.print "Hello, World! $invoke"
        session.setAttribute('invoke', invoke+1)
    }
}