# CSE471 Transparent HTTP/HTTPS Proxy

## Project Overview

This project implements a transparent HTTP/HTTPS proxy system for the CSE471 course project. The system consists of a Java-based proxy server and a DNS service. The DNS service redirects client requests to the proxy, and the proxy then handles HTTP and HTTPS traffic according to the project requirements.

The proxy supports HTTP request forwarding, HTTPS tunneling using SNI, host-based filtering, disk caching, request logging, report generation, and a Java Swing graphical user interface. The project can be run with Docker Compose or directly from a Java IDE such as Eclipse.



## Main Features

The project implements the following functionality:

```text
DNS-based redirection with dnsmasq
Transparent HTTP proxying
HTTPS tunneling using TLS SNI parsing
Support for GET, HEAD, OPTIONS, and POST methods
405 Method Not Allowed response for unsupported methods
Host filtering with 401 Unauthorized response
Disk-based HTTP caching
Last-Modified / If-Modified-Since cache validation
Streaming response forwarding for large response bodies
Client request logging
Per-client TXT report generation
Java Swing GUI
Bonus token-based filtering mode control
Docker Compose setup
```

## Repository Structure

The project has the following structure:

```text
.
├── docker-compose.yml
├── README.md
├── dns/
│   ├── Dockerfile
│   └── dnsmasq.conf
└── proxy/
    ├── Dockerfile
    ├── build.sh
    └── src/
        ├── Main.java
        ├── ProxyGui.java
        ├── ProxyServer.java
        ├── ProxyContext.java
        ├── HttpHandler.java
        ├── HttpsHandler.java
        ├── SniParser.java
        ├── DiskCache.java
        ├── FilterStore.java
        └── ClientLog.java
```

The `dns/` directory contains the DNS service configuration. The `proxy/` directory contains the Java proxy implementation.

## Component Responsibilities

### DNS Component

The DNS component is implemented using `dnsmasq`.

Relevant files:

```text
dns/Dockerfile
dns/dnsmasq.conf
```

The purpose of the DNS service is to redirect domain name resolutions to the proxy container. In a transparent proxy setup, the client does not manually specify the proxy. Instead, DNS returns the proxy IP address, so the client connects to the proxy while believing it is connecting to the original destination.

### Proxy Component

The proxy is implemented in Java.

Relevant files:

```text
proxy/src/Main.java
proxy/src/ProxyServer.java
proxy/src/HttpHandler.java
proxy/src/HttpsHandler.java
proxy/src/SniParser.java
proxy/src/DiskCache.java
proxy/src/FilterStore.java
proxy/src/ClientLog.java
proxy/src/ProxyGui.java
```

The proxy component listens for HTTP and HTTPS connections, processes requests, applies filtering rules, forwards allowed traffic, caches HTTP responses, and records request logs.

## Source File Explanation

### `Main.java`

`Main.java` is the entry point of the application. It parses command-line arguments, initializes the shared proxy context, and starts either the GUI mode or the headless proxy mode.

It supports custom port arguments such as:

```text
--http-port 8080 --https-port 8443
```

This is useful on macOS/Linux because ports below 1024 may require administrator privileges.

### `ProxyServer.java`

`ProxyServer.java` is responsible for opening the HTTP and HTTPS listening sockets. It accepts incoming client connections and dispatches them to the appropriate handler.

The HTTP server handles plaintext HTTP traffic, while the HTTPS server accepts TLS connections and delegates them to the HTTPS handler.

### `HttpHandler.java`

`HttpHandler.java` handles HTTP requests. Its responsibilities include:

```text
Parsing HTTP request lines and headers
Supporting GET, HEAD, OPTIONS, and POST
Rejecting unsupported methods with 405 Method Not Allowed
Checking whether the requested host is filtered
Returning 401 Unauthorized for filtered hosts
Forwarding allowed requests to upstream servers
Handling token login requests
Using the disk cache when possible
Writing request logs
```

### `HttpsHandler.java`

`HttpsHandler.java` handles HTTPS traffic. Since HTTPS is encrypted, the proxy does not inspect the full HTTP request body. Instead, it reads the TLS ClientHello message, extracts the SNI hostname, and opens a tunnel to the target server.

The HTTPS traffic is then relayed between the client and the destination server.

### `SniParser.java`

`SniParser.java` extracts the Server Name Indication value from the TLS ClientHello message. This is necessary for HTTPS proxying because the proxy must know which host the client is trying to reach before it can open the tunnel.

### `DiskCache.java`

`DiskCache.java` manages the disk-based HTTP cache. It stores response body files and metadata files. The metadata includes information such as:

```text
URL
status code
Last-Modified value
response headers
```

This allows the proxy to reuse cached responses and validate them with `If-Modified-Since`.

### `FilterStore.java`

`FilterStore.java` manages the filtered host list and token-based filtering mode. It stores filtered hosts and controls whether filtering is enabled or disabled for a specific client.

Two tokens are used:

```text
8a21bce200 -> disable filtering for the client
51e2cba401 -> enable filtering for the client
```

### `ClientLog.java`

`ClientLog.java` records client request information and generates per-client reports. The log stores information such as:

```text
timestamp
client IP
host
path
HTTP method
status code
```

It also supports exporting filtered log entries into a TXT report file.

### `ProxyGui.java`

`ProxyGui.java` implements the Java Swing GUI. The GUI provides menu options for starting and stopping the proxy, adding filtered hosts, displaying the current filter list, generating reports, and showing developer information.

## Running the Project with Docker Compose

Make sure Docker Desktop is running before starting the project.

Open a terminal in the project root directory, where `docker-compose.yml` is located.

Start the DNS and proxy services:

```bash
docker compose up --build dns proxy
```

This starts the following main services:

```text
cse471-dns
cse471-proxy
```

Check that the containers are running:

```bash
docker ps
```

Expected result:

```text
cse471-dns      Up
cse471-proxy    Up
```

To stop the services:

```bash
docker compose down
```

## Docker Port Mapping

Inside the Docker container, the proxy listens on the standard HTTP and HTTPS ports:

```text
HTTP  -> 80
HTTPS -> 443
```

On the macOS host machine, these are mapped to non-privileged ports:

```text
localhost:8080 -> container port 80
localhost:8443 -> container port 443
```

Therefore, when testing from the Mac host, use:

```text
HTTP  -> localhost:8080
HTTPS -> localhost:8443
```

This mapping is only for host-side convenience. The proxy still uses the standard HTTP/HTTPS ports inside the container.

## Running the Java GUI from Eclipse

The proxy can also be run directly from Eclipse.

Steps:

```text
1. Open Eclipse.
2. Create a new Java project.
3. Import or copy the Java files from proxy/src into the project src folder.
4. If Eclipse creates module-info.java, remove it.
5. Open Run Configurations.
6. Select the Main Java application.
7. Go to the Arguments tab.
8. Add the following program arguments:
```

```text
--http-port 8080 --https-port 8443
```

Then run `Main.java`.

The GUI should open and show the proxy status.

The GUI contains the following menu options:

```text
File -> Start
File -> Stop
File -> Report
File -> Add host to filter
File -> Display current filtered hosts
File -> Exit
Help -> About
```

To start the proxy from the GUI, select:

```text
File -> Start
```

To stop the proxy:

```text
File -> Stop
```

## Token-Based Filtering Mode

The proxy includes a token-based filtering mode.

The following tokens are supported:

```text
8a21bce200 -> filtering disabled for the client
51e2cba401 -> filtering enabled for the client
```

### Disable Filtering

```bash
curl -v -X POST http://localhost:8080/__proxy_login -d "token=8a21bce200"
```

Expected response:

```text
Filtering disabled for this client.
```

### Enable Filtering

```bash
curl -v -X POST http://localhost:8080/__proxy_login -d "token=51e2cba401"
```

Expected response:

```text
Filtering enabled for this client.
```

## HTTP Request Tests

### GET Request

```bash
curl -v -x http://localhost:8080 http://example.com/
```

Expected behavior:

```text
The request is sent to the proxy.
The proxy forwards the request to example.com.
The response contains the Example Domain page.
```

Expected status:

```text
HTTP/1.1 200 OK
```

### HEAD Request

```bash
curl -I -x http://localhost:8080 http://example.com/
```

Expected behavior:

```text
Only response headers are returned.
The request is not rejected by the proxy.
```

Expected status may be:

```text
HTTP/1.1 200 OK
```

or, if cache validation is used:

```text
HTTP/1.1 304 Not Modified
```

### DELETE Request

`DELETE` is not one of the supported methods, so the proxy should reject it.

```bash
curl -v -x http://localhost:8080 -X DELETE http://example.com/
```

Expected response:

```text
HTTP/1.1 405 Method Not Allowed
Allow: GET, HEAD, OPTIONS, POST
```

This confirms that unsupported HTTP methods are blocked by the proxy.

### POST Request

```bash
curl -v -x http://localhost:8080 -X POST http://httpbin.org/post -d "name=test"
```

Expected behavior:

```text
The proxy should not generate its own 405 Method Not Allowed response.
The request should be forwarded to the upstream server.
```

The final response may depend on the upstream server.

### OPTIONS Request

```bash
curl -v -x http://localhost:8080 -X OPTIONS http://example.com/
```

Expected behavior:

```text
The proxy should not reject OPTIONS as an unsupported method.
The request should be forwarded upstream.
```

The final status code may depend on the upstream server.

## Host Filtering

Hosts can be added to the filter list through the GUI.

Use:

```text
File -> Add host to filter
```

For example, add:

```text
example.com
```

To display the filter list:

```text
File -> Display current filtered hosts
```

After adding a host to the filter list, enable filtering for the client:

```bash
curl -v -X POST http://localhost:8080/__proxy_login -d "token=51e2cba401"
```

Then test the filtered host:

```bash
curl -v -x http://localhost:8080 http://example.com/
```

Expected response:

```text
HTTP/1.1 401 Unauthorized
```

This shows that the proxy correctly blocks filtered hosts.

## HTTPS / SNI Proxying

HTTPS traffic is encrypted, so the proxy cannot read the full HTTP request like it does for plaintext HTTP. Instead, the proxy reads the TLS ClientHello message and extracts the SNI hostname.

The proxy then opens a TCP connection to the destination host and relays encrypted data between the client and the server.

Test command:

```bash
curl -vk --resolve example.com:8443:127.0.0.1 https://example.com:8443/
```

Explanation:

```text
--resolve example.com:8443:127.0.0.1 forces curl to connect to localhost.
The TLS SNI hostname remains example.com.
The proxy reads the SNI value and tunnels the connection to example.com.
```

Expected behavior:

```text
TLS handshake succeeds.
The response is received from example.com.
The body contains Example Domain.
```

## DNS Redirection Test

The DNS container can be tested with:

```bash
docker exec cse471-dns nslookup example.com 127.0.0.1
```

Expected behavior:

```text
example.com resolves to the proxy container IP address.
```

This confirms that the DNS service redirects domain names to the proxy.

## Caching Behavior

The proxy supports disk-based caching for HTTP responses. When a response includes cache-related metadata such as `Last-Modified`, the proxy stores the response body and metadata on disk.

In Docker, cache files are stored under:

```text
/data/cache/
```

The cache directory contains files such as:

```text
*.body
*.meta
```

The `.body` file stores the response body. The `.meta` file stores metadata such as:

```text
url
status
last_modified
response headers
```

A repeated request may return:

```text
X-Proxy-Cache: HIT
```

This indicates that the response was served from the proxy cache.

Cache metadata can be inspected with:

```bash
docker exec cse471-proxy sh -c 'cat /data/cache/*.meta'
```

Example metadata:

```text
url=http://example.com/
status=200
last_modified=Fri, 05 Jun 2026 20:00:44 GMT
h.content-type=text/html
h.last-modified=Fri, 05 Jun 2026 20:00:44 GMT
```

## Logging

The proxy logs client requests.

In Docker, the log file is stored at:

```text
/data/client_log.tsv
```

The log can be inspected with:

```bash
docker exec cse471-proxy sh -c 'cat /data/client_log.tsv'
```

Each log entry contains:

```text
timestamp
client IP
host
path
HTTP method
status code
```

Example:

```text
2026-06-06 13:04:58    127.0.0.1    example.com    /    DELETE    405
```

This shows that the proxy records client activity and response status codes.

## Report Generation

The GUI can generate a per-client TXT report.

Use:

```text
File -> Report
```

The application asks for a client IP address. For local tests, the client is usually:

```text
127.0.0.1
```

or, depending on the Java network stack:

```text
0:0:0:0:0:0:0:1
```

The generated report is saved as a TXT file, for example:

```text
report_127.0.0.1.txt
```

The report contains the log entries for the selected client.

## Browser Testing

The proxy can be tested with a browser such as Firefox.

A simple browser test can be done by configuring Firefox to use the proxy manually:

```text
HTTP Proxy: localhost
Port: 8080
```

Then open:

```text
http://example.com
```

If the client has not authenticated with a token yet, the proxy authentication page should be displayed. After a valid token is submitted, normal browsing can continue.

For a more transparent DNS-based browser test, a Linux lab machine or virtual machine is recommended because DNS and standard port behavior can be controlled more easily than on macOS.

## Large File Support

The proxy forwards response bodies as streams instead of loading the full response body into memory at once. This design allows the proxy to handle large responses more safely and is the intended mechanism for supporting large file downloads.

## Manual Tests Performed

The following tests were performed during manual verification:

```text
Docker DNS/proxy startup
docker ps container status check
DNS redirection using nslookup
HTTP GET request through proxy
HTTP HEAD request through proxy
DELETE request returning 405 Method Not Allowed
POST request forwarding behavior
OPTIONS request forwarding behavior
Token login with filtering disabled
Token login with filtering enabled
Filtered host returning 401 Unauthorized
Cache file creation
Cache metadata inspection
X-Proxy-Cache HIT behavior
Client log file inspection
HTTPS/SNI tunnel test
Java GUI startup in Eclipse
GUI Start operation
GUI Stop operation
GUI Add host to filter
GUI Display current filtered hosts
GUI Report generation
```

## Notes on Tests Not Fully Stress-Tested

The implementation includes support for browser-based use and streaming large response bodies. However, the following items were not stress-tested in the manual verification session:

```text
Full Firefox transparent DNS-based browser demo
Real >500 MB file download stress test
HTTPS filtered-host blocking test
```

The proxy was tested primarily with `curl`, Docker, DNS checks, and the Java GUI.

## Troubleshooting

### Docker is installed but not running

If this command fails:

```bash
docker ps
```

with a Docker daemon connection error, start Docker Desktop and run the command again.

### Port already in use

If the GUI shows a port binding error, another process may already be using the selected ports. Docker may also be running the proxy on the same host ports.

Stop Docker services if necessary:

```bash
docker compose down
```

Then run the Java GUI with:

```text
--http-port 8080 --https-port 8443
```

### Ports below 1024 on macOS/Linux

Ports such as `80` and `443` may require administrator privileges when running directly from Java. Use `8080` and `8443` for local GUI testing.

### Tester container

The main project services are `dns` and `proxy`. If an optional tester container is present and fails in a specific environment, the project can still be tested manually from the host using `curl` or from inside the proxy container.

Recommended startup command for the main services:

```bash
docker compose up --build dns proxy
```

## Stopping the Project

To stop Docker services:

```bash
docker compose down
```

To stop the GUI proxy:

```text
File -> Stop
```

Then close the GUI window.

