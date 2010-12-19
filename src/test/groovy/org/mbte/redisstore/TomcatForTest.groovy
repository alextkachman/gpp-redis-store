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

import java.util.concurrent.CountDownLatch
import org.apache.catalina.valves.PersistentValve
import org.apache.catalina.session.PersistentManager
import org.apache.catalina.core.StandardContext
import javax.servlet.http.HttpServlet
import org.apache.catalina.startup.Tomcat

@Typed class TomcatForTest extends Tomcat {
    void start(Class<HttpServlet> servletClass) {
        def temp = File.createTempFile("tomcat-", "-dir")
        temp.delete()

        temp.mkdirs()
        temp.deleteOnExit()

        baseDir = temp.canonicalPath
        new File("$basedir/webapps").mkdir()

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

