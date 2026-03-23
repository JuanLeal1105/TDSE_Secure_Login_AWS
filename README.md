# **Secure Application Design**

**Created by**

Juan Carlos Leal Cruz

## **Laboratory Overview**
 
This project implements a secure web application that satisfies the following requirements:
 
- Asynchronous frontend — a single HTML + JavaScript page that communicates with the backend using `fetch`, no page reloads
- Spring Boot REST API that handles user registration, login, and protected routes
- JWT-based stateless authentication — no sessions are stored on the server
- BCrypt password hashing — passwords are never stored in plain text
- Apache HTTP Server as a reverse proxy between the client and the backend
- HTTPS enforced in production via Let's Encrypt
- Everything runs in Docker containers — no manual installation of Java, Maven, or Apache on the host machine
- Deployed on AWS EC2 with a public domain via DuckDNS

### **Prerequisites**
#### **For local development:**
- Java 21
- Maven 3.9+
- Docker Desktop

#### **For AWS deployment:**
- An AWS account with an EC2 instance running Amazon Linux 2023
- A DuckDNS account with a subdomain pointing to the EC2 public IP
- Docker and Docker Compose installed on the EC2

### **Project Structure**
```
securelogin/
├── Dockerfile                           
├── docker-compose.yml                   
├── docker-compose.prod.yml              
├── pom.xml                              
├── .env       #It's not in the repository for secure concerns but, it has to be in the root folder
├── .gitignore                           
├── README.md
├── frontend/
│   └── index.html                      
└── src/main/
    ├── java/com/securelogin/
    │   ├── SecureLoginApplication.java  
    │   ├── config/
    │   │   ├── SecurityConfig.java  
    │   │   └── GlobalExceptionHandler.java
    │   ├── controller/
    │   │   ├── AuthController.java    
    │   │   └── UserController.java    
    │   ├── dto/
    │   │   ├── request /
    │   │   │   ├── LoginRequest.java
    │   │   │   └── RegisterRequest.java
    │   │   └── response /
    │   │       ├── AuthResponse.java
    │   │       └── MessageResponse.java
    │   ├── model/
    │   │   └── User.java               
    │   ├── repository/
    │   │   └── UserRepository.java     
    │   ├── security/
    │   │   ├── JwtUtils.java           
    │   │   ├── JwtAuthFilter.java      
    │   │   └── UserDetailsServiceImpl.java
    │   └── service/
    │       └── AuthService.java       
    └── resources/
        ├── application.properties      
        └── apache/
            ├── httpd.local.conf        
            └── httpd.prod.conf        
```

---
## **Architecture**
The application follows a three-tier distributed architecture. All three tiers run as Docker containers on the same host, isolated from each other on a private Docker bridge network, with only Apache exposed to the public internet.
 
### **Presentation tier — Apache HTTP Server** 
Is the single entry point for all traffic on ports 80 and 443. It serves the static `index.html` directly and reverse-proxies any request under `/api/*` to the Spring Boot container using `mod_proxy_http`. It also handles TLS termination — meaning it decrypts HTTPS traffic from the browser and forwards it to Spring Boot over plain HTTP on the internal network, a pattern called TLS offloading that keeps certificate management out of the application layer.
 
### **Application tier — Spring Boot**
Is a stateless REST API running on Java 21. Stateless means it holds no session data between requests — everything the server needs to process a request is either in the JWT or in the database. This makes the service horizontally scalable and removes the need for a shared session store. Port 8080 is declared with `expose` rather than `ports`, so it is only reachable within the Docker network.
 
### **Data tier — PostgreSQL** 
Stores a single `users` table with username, email, BCrypt-hashed password, full name, and timestamps. It is also only reachable internally. Data persists across container restarts via a named Docker volume. Spring Boot connects via JDBC using HikariCP as the connection pool to avoid the overhead of creating a new database connection on every request.
 
### **Request lifecycle.** 
A login request starts at the browser over HTTPS and reaches Apache on port 443, which decrypts it and forwards it to the Spring Boot application on port 8080. The request passes through `JwtAuthFilter` since it is a public route, then reaches `AuthController`, `AuthService`, and `AuthenticationManager`, where the password is checked against the BCrypt hash in PostgreSQL. If the credentials are valid, `JwtUtils.generateToken()` creates a token, and the response is returned to the browser through the same path.


## **API Endpoints**
All endpoints are prefixed with `/api` so Apache can identify and forward them correctly. The backend exposes three endpoints across two controllers.
 
### **POST /api/auth/register**
The `@Valid` annotation triggers Bean Validation before the method body executes, so invalid fields (Ex: password under 8 characters) return a `400` automatically without custom error-handling code. If validation passes, `AuthService` checks for duplicate usernames and emails, hashes the password with BCrypt, and persists the user.
 
```json
Body:    { "username": "juan", "email": "juan@example.com", "password": "pass1234", "fullName": "Juan García" }
200 OK:  { "message": "User registered successfully." }
400:     { "password": "size must be between 8 and 72" }
```
 
### **POST /api/auth/login**
Delegates to Spring Security's `AuthenticationManager`, which loads the user via `UserDetailsServiceImpl` and compares passwords using `BCryptPasswordEncoder.matches()`. The application code never touches the raw password after it arrives — Spring Security handles all credential comparison. On success, `JwtUtils.generateToken()` returns a signed JWT.
 
```json
Body:    { "username": "juan", "password": "pass1234" }
200 OK:  { "token": "eyJhbGci...", "tokenType": "Bearer", "expiresIn": 86400000 }
401:     { "message": "Invalid username or password." }
```
 
### **GET /api/user/me**
Requires `Authorization: Bearer <token>`. The `JwtAuthFilter` validates the token before the request reaches the controller. If the token is missing, expired, or has an invalid signature, Spring Security returns `403` before the controller method is invoked. Returns an empty `200 OK` body to confirm the token is valid.
 
```
200 OK  — token is valid
403     — token is missing, expired, or tampered with
```
---
## Security Features
### **Password hashing (BCrypt).**  
Passwords are securely hashed using BCrypt with a cost factor of 12 (4096 iterations). This makes hashing intentionally slow, which helps protect against brute-force attacks. Each password is also salted automatically, so identical passwords never produce the same hash. The original password is never stored or exposed—Spring Security handles verification safely during login.

### **Secret management.**  
Sensitive data like the JWT secret and database password are stored in environment variables, not in the codebase. Docker Compose loads them from a `.env` file at runtime. This file is excluded from version control.

### **TLS (secure connections).**  
The server only allows modern, secure protocols (TLS 1.2 and 1.3). Older, insecure versions are disabled. It also uses strong encryption (ECDHE) that ensures forward secrecy—meaning even if a key is compromised later, past data remains secure. All HTTP traffic is automatically redirected to HTTPS.

### **HTTP security headers.**  
Several headers are enabled to protect users:
- `Strict-Transport-Security`: forces HTTPS for 2 years
- `X-Frame-Options: DENY`: prevents clickjacking
- `X-Content-Type-Options: nosniff`: blocks MIME-type attacks
- `Referrer-Policy`: limits data shared with other sites

### **Network isolation & container security.**  
Only the web server (Apache) is publicly accessible. The database and backend are hidden inside the Docker network. The application also runs as a non-root user, reducing the risk if the container is compromised.

### **CORS policy.**  
Only approved frontend origins (set via environment variables) can access the API. Requests from other origins are blocked early, preventing unauthorized cross-site requests.

---
## **Apache Configuration**
Apache configuration files are stored in `src/main/resources/apache/` and mounted into the `httpd:2.4-alpine` container using Docker volumes. This lets you update Apache settings by just restarting the container—no need to rebuild the image.

### **Local (`httpd.local.conf`)**  
- Loads only the modules needed for serving static files and proxying.  
- No SSL modules, so no certificate errors.  
- `VirtualHost *:80` serves `index.html` from `frontend/`.  
- `FallbackResource /index.html` ensures SPA routing works.  
- `/api` requests are forwarded to `http://login-service:8080` using Docker’s internal DNS.

### **Production (`httpd.prod.conf`)**  
- Adds SSL support with `mod_ssl`.  
- HTTP (`:80`) redirects all traffic to HTTPS (`:443`) with a `301`.  
- HTTPS uses Let's Encrypt certificates mounted from `/etc/letsencrypt`.  
- Only TLS 1.2 and 1.3 are allowed, with security headers enabled.  
- Special settings:  
  - `SSLUseStapling off` avoids errors with DuckDNS Let’s Encrypt certificates.  
  - `User daemon` / `Group daemon` fixes Alpine warnings about missing web server users.


## Docker Setup
 
### **Dockerfile — multi-stage build.** 
Stage one uses `maven:3.9.6-eclipse-temurin-21` to compile the project. It copies `pom.xml` and runs `mvn dependency:go-offline` first, creating a cached Docker layer for dependencies that is reused on subsequent builds when only source files changed. Stage two uses `eclipse-temurin:21-jre-jammy` — a minimal JRE image with no compiler or build tools — copies only the compiled JAR, and runs it as a non-root `appuser`. This reduces the final image size from ~600 MB (if the JDK and Maven were included) to ~250 MB.
 
### **docker-compose.yml (local)** 
It defines three services. `postgres` uses a health check (`pg_isready`) and a named volume for persistence. `login-service` declares `depends_on: condition: service_healthy` so Spring Boot waits for PostgreSQL to be ready before starting, avoiding connection errors on cold starts. It receives all config via environment variables that override the `application.properties` defaults, and uses `expose: "8080"` so the port is internal only. `apache` mounts the local HTTP config and the `frontend/` folder and maps port 80 to the host.
 
### **docker-compose.prod.yml (production)** 
Is identical to the local version with two differences: Apache uses `httpd.prod.conf` instead of the local config, and it maps port 443 in addition to port 80, with `/etc/letsencrypt` mounted read-only from the host so the container can access the TLS certificates.
 
## **Environment variables** 
Those are injected at runtime from `.env`, a file that is never committed.
 
| Variable | Required | Description | How to generate |
|---|---|---|---|
| `DB_NAME` | No (default: logindb) | PostgreSQL database name | Any string |
| `DB_USER` | No (default: loginuser) | PostgreSQL username | Any string |
| `DB_PASSWORD` | **Yes** | PostgreSQL password | `openssl rand -base64 32` |
| `JWT_SECRET` | **Yes** | HMAC-SHA256 signing key — min 64 chars | `openssl rand -hex 64` |
| `JWT_EXPIRATION_MS` | No (default: 86400000) | Token lifetime in ms | 86400000 = 24 h |
| `CORS_ALLOWED_ORIGINS` | **Yes** | Allowed frontend origin | `https://yourdomain.duckdns.org` |

---
## How to run the project?
### Running Locally
#### **Option A — Without Docker (fastest for quick testing)**
 
```bash
mvn spring-boot:run
```
 
This uses an H2 in-memory database automatically. The API is at `http://localhost:8080` and the H2 console is at `http://localhost:8080/h2-console` (user: `sa`, password: empty). Open `frontend/index.html` directly in the browser and set `API_BASE = 'http://localhost:8080'` in the script.
 
#### **Option B — Full stack with Docker**
1. Create your .env
   ```bash
   # Fill in DB_PASSWORD and JWT_SECRET
   # Generate them with:
   #   openssl rand -base64 32   DB_PASSWORD
   #   openssl rand -hex 64      JWT_SECRET
   ```
2. Start everyhting
   ```bash
   docker compose up -d --build
   ```
3. Check that Spring Boot started
   ```bash
   docker compose logs -f login-service
   ```
   After that, look for: Started SecureLoginApplication
4. Open the app at `htttp://localhost`
   
   First you need to make sure that the variable `API_BASE` in the file `index.html` is set to `http://localhost`

#### **Useful Commands**
``` bash
docker compose ps                            # check container status
docker compose logs -f login-service         # Spring Boot logs
docker compose logs apache                   # Apache logs
docker compose stop                          # stop (data is preserved)
docker compose start                         # start again
docker compose down -v                       # stop and wipe the database
docker compose up -d --build login-service   # rebuild only the backend
```
---
## Deploying to AWS
### Step by step
 
#### **1. Install dependencies on the EC2**
 
```bash
sudo dnf update -y
sudo dnf install -y git docker python3-pip
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user
newgrp docker
 
# Docker Compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/download/v2.24.6/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
 
# Certbot
sudo pip3 install certbot
```
 
#### **2. Point your DuckDNS domain to the EC2 public IP**
 
Log in to duckdns.org, enter your EC2 public IPv4 address in the IP field, and click Update IP.
 
#### **3. Get the HTTPS certificate (port 80 must be free — run before Docker)**
 
```bash
sudo certbot certonly --standalone \
  -d yourdomain.duckdns.org \
  --email you@example.com \
  --agree-tos \
  --no-eff-email
```
 
#### **4. Clone the repository and create .env**
 
```bash
git clone https://github.com/yourusername/securelogin.git
cd securelogin
nano .env
# Set DB_PASSWORD, JWT_SECRET, and CORS_ALLOWED_ORIGINS=https://yourdomain.duckdns.org
```
 
#### **5. Set your domain in the config files**
```bash
sed -i 's/yourdomain.com/yourdomain.duckdns.org/g' \
  src/main/resources/apache/httpd.prod.conf
 
sed -i 's|http://localhost|https://yourdomain.duckdns.org|g' \
  frontend/index.html
```
 
#### **6. Start the production stack**
 
```bash
docker compose -f docker-compose.prod.yml up -d --build
```
 
#### **7. Verify**
 
```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f login-service
sudo docker logs login_apache
```
 
Open `https://yourdomain.duckdns.org` in your browser.
 
#### **Daily operations**
 
```bash
# Stop containers (database is preserved)
docker compose -f docker-compose.prod.yml stop
 
# Start again
docker compose -f docker-compose.prod.yml start
 
# Check database
sudo docker exec -it login_postgres psql -U loginuser -d logindb -c "SELECT * FROM users;"
```
 
