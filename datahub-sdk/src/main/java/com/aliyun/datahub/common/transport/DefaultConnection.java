/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.datahub.common.transport;

import com.aliyun.datahub.DatahubConfiguration;
import com.aliyun.datahub.auth.AuthorizationUtil;
import com.aliyun.datahub.exception.LimitExceededException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * DefaultConnection通过JDK中 HttpURLConnection}实现HTTP连接
 */
public class DefaultConnection implements Connection {

    private static final Logger log = Logger.getLogger(DefaultConnection.class.getName());

    private HttpURLConnection conn;
    private DatahubConfiguration conf;

    public DefaultConnection(DatahubConfiguration conf) {
        this.conf = conf;
    }

    @Override
    public void connect(DefaultRequest req) throws IOException {

        URI u;
        u = URI.create(this.conf.getEndpoint() + req.getResource());
        if (log.isLoggable(Level.FINE)) {
            log.fine("Connecting to " + u.toString());
        }
        if (u.getScheme() == null) {
            IOException e = new IOException("Request URI(http or https) required.");
            log.log(Level.SEVERE, e.getMessage(), e);
            throw e;
        }

        try {
            String scheme = u.getScheme().toLowerCase();
            if (scheme.startsWith("http") || scheme.startsWith("https") || scheme.startsWith("test")) {
                conn = (HttpURLConnection) u.toURL().openConnection();
            } else {
                throw new IOException("Protocol not supported: " + u.getScheme());
            }

            // set input/output flags
            conn.setDoInput(true);
            conn.setDoOutput(true);

            // setTimeout
            conn.setReadTimeout(this.conf.getSocketTimeout() * 1000);
            conn.setConnectTimeout(this.conf.getSocketConnectTimeout() * 1000);

            // set request method
            conn.setRequestMethod(req.getHttpMethod().toString());

            // set HTTPS ignored certs
            AuthorizationUtil.ignoreHttpsCerts(conn);

            // set content-length
            if (req.getBody() != null) {
                long bodyLength = req.getBody().length;
                conn.setRequestProperty("Content-Length", String.valueOf(bodyLength));
                // XXX Max file 2G, bodyLength loss precision
                conn.setFixedLengthStreamingMode((int) bodyLength);
            }

            // set headers
            if (req.getHeaders() != null) {
                if (log.isLoggable(Level.FINE)) {
                    log.fine("Request headers: " + req.getHeaders().toString());
                }

                for (Entry<String, String> kv : req.getHeaders().entrySet()) {
                    conn.setRequestProperty(kv.getKey(), kv.getValue());
                    if (Headers.TRANSFER_ENCODING.equalsIgnoreCase(kv.getKey())
                            && Headers.CHUNKED.equalsIgnoreCase(kv.getValue())) {
                        conn.setChunkedStreamingMode(1500 - 4); // XXX: hardcode
                    }
                }
            }

        } catch (MalformedURLException e) {
            log.log(Level.SEVERE, e.getMessage(), e);
            throw new IllegalArgumentException("Invalid request URI: " + u);
        }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        checkConnection();
        return conn.getOutputStream();
    }

    @Override
    public Response getResponse() throws IOException {
        checkConnection();
        DefaultResponse resp = new DefaultResponse();

        resp.setStatus(conn.getResponseCode());
        Map<String, List<String>> fields = conn.getHeaderFields();
        Map<String, String> headers = resp.getHeaders();
        for (Entry<String, List<String>> kv : fields.entrySet()) {
            StringBuilder sb = new StringBuilder();
            String pad = "";
            for (String v : kv.getValue()) {
                sb.append(pad).append(v);
                pad = ",";
            }
            headers.put(kv.getKey(), sb.toString());
        }
        resp.setBody(conn.getResponseMessage().getBytes());
        return resp;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        checkConnection();

        InputStream is;
        if (conn.getResponseCode() / 100 < 4) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
        }

        if (is == null) {
            throw new IOException("Invalid connection.");
        }

        String encoding = conn.getHeaderField(Headers.CONTENT_ENCODING);
        if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
            is = new GZIPInputStream(is);
        }
        return is;
    }

    @Override
    public void disconnect() throws IOException {
        checkConnection();
        conn.disconnect();
    }

    private void checkConnection() throws IOException {
        if (conn == null) {
            throw new IOException("Invalid connection.");
        }
    }
}
