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


