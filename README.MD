# Patient Management System

A microservices-based healthcare patient management system built with Spring Boot, featuring JWT authentication, gRPC communication, event-driven architecture with Kafka, and cloud-ready infrastructure with AWS CDK/LocalStack.

## 📋 Table of Contents

- [Architecture Overview](#architecture-overview)
- [Services](#services)
- [Technology Stack](#technology-stack)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [API Endpoints](#api-endpoints)
- [Service Communication](#service-communication)
- [Infrastructure](#infrastructure)
- [Testing](#testing)
- [Project Structure](#project-structure)

## 🏗️ Architecture Overview

This project supports two deployment modes: **Docker Compose** (local development) and **AWS/LocalStack** (cloud deployment).

### Docker Compose Architecture (Local Development)

```
                                 ┌──────────────────┐
                                 │    API Gateway   │
                                 │   (Port: 4004)   │
                                 └────────┬─────────┘
                                          │ HTTP
            ┌─────────────────────────────┼─────────────────────────────┐
            │                             │                             │
            ▼                             ▼                             │
  ┌──────────────────┐         ┌──────────────────┐                     │
  │   Auth Service   │         │  Patient Service │                     │
  │   (Port: 4005)   │         │   (Port: 4000)   │                     │
  └────────┬─────────┘         └───┬─────────┬────┘                     │
           │                       │         │                          │
           │                       │ gRPC    │ Kafka                    │
           │                       ▼         ▼                          │
           │              ┌────────────────────────────┐                │
           │              │                            │                │
           │    ┌─────────▼────────┐     ┌─────────────▼──────┐         │
           │    │  Billing Service │     │  Analytics Service │         │
           │    │ (Port: 4002/9001)│     │    (Port: 4003)    │         │
           │    └──────────────────┘     └────────────────────┘         │
           │                                                            │
           ▼                             ▼                              │
  ┌──────────────────┐         ┌──────────────────┐                     │
  │     AuthDB       │         │    PatientDB     │                     │
  │   (PostgreSQL)   │         │   (PostgreSQL)   │                     │
  │   Port: 5001     │         │    Port: 5000    │                     │
  └──────────────────┘         └──────────────────┘                     │
                                                                        │
                               ┌──────────────────┐                     │
                               │   Apache Kafka   │◄────────────────────┘
                               │  (Port: 9092)    │
                               └──────────────────┘
```

### AWS/LocalStack Architecture (Cloud Deployment)

```
                                    ┌─────────────────────────────────────┐
                                    │              AWS VPC                │
                                    │       (PatientManagementVPC)        │
                                    │            2 AZs                    │
┌─────────┐                         │  ┌───────────────────────────────┐  │
│         │    HTTP                 │  │    Application Load Balancer  │  │
│ Client  │ ───────────────────────────►       (Public Subnet)         │  │
│         │                         │  └──────────────┬────────────────┘  │
└─────────┘                         │                 │                   │
                                    │  ┌──────────────▼────────────────┐  │
                                    │  │        ECS Cluster            │  │
                                    │  │   (patient-management.local)  │  │
                                    │  │                               │  │
                                    │  │  ┌─────────────────────────┐  │  │
                                    │  │  │   API Gateway (Fargate) │  │  │
                                    │  │  │       Port: 4004        │  │  │
                                    │  │  └───────────┬─────────────┘  │  │
                                    │  │              │                │  │
                                    │  │   ┌─────────┴─────────┐       │  │
                                    │  │   │                   │       │  │
                                    │  │   ▼                   ▼       │  │
                                    │  │ ┌───────────┐ ┌─────────────┐ │  │
                                    │  │ │Auth Svc   │ │Patient Svc  │ │  │
                                    │  │ │(Fargate)  │ │(Fargate)    │ │  │
                                    │  │ │Port: 4005 │ │Port: 4000   │ │  │
                                    │  │ └─────┬─────┘ └──┬─────┬────┘ │  │
                                    │  │       │          │     │      │  │
                                    │  │       │    gRPC  │     │Kafka │  │
                                    │  │       │          ▼     │      │  │
                                    │  │       │  ┌────────────┐│      │  │
                                    │  │       │  │Billing Svc ││      │  │
                                    │  │       │  │(Fargate)   ││      │  │
                                    │  │       │  │Port: 9001  ││      │  │
                                    │  │       │  └────────────┘│      │  │
                                    │  │       │                │      │  │
                                    │  │       │         ┌──────▼────┐ │  │
                                    │  │       │         │Analytics  │ │  │
                                    │  │       │         │Svc        │ │  │
                                    │  │       │         │(Fargate)  │ │  │
                                    │  │       │         │Port: 4003 │ │  │
                                    │  │       │         └───────────┘ │  │
                                    │  └───────┼───────────────────────┘  │
                                    │          │                          │
                                    │  ┌───────▼───────┐  ┌────────────┐  │
                                    │  │ RDS PostgreSQL│  │ MSK Kafka  │  │
                                    │  │ (AuthDB)      │  │ Cluster    │  │
                                    │  │               │  │ (2 Brokers)│  │
                                    │  ├───────────────┤  │            │  │
                                    │  │ RDS PostgreSQL│  │            │  │
                                    │  │ (PatientDB)   │  │            │  │
                                    │  └───────────────┘  └────────────┘  │
                                    │                                     │
                                    └─────────────────────────────────────┘
```

### AWS/LocalStack Components

| Component | AWS Service | Description |
|-----------|-------------|-------------|
| **VPC** | Amazon VPC | Isolated network with 2 Availability Zones |
| **Load Balancer** | Application Load Balancer | Entry point for all traffic |
| **Microservices** | ECS Fargate | Serverless containers (256 CPU, 512MB RAM) |
| **Databases** | RDS PostgreSQL 16.3 | Managed databases with auto-generated secrets |
| **Message Broker** | MSK (Kafka 3.5.1) | Managed Kafka with 2 broker nodes |
| **Service Discovery** | Cloud Map | Internal DNS (*.patient-management.local) |
| **Logging** | CloudWatch Logs | Centralized logging for all services |

### Flow Description
1. **Client** sends requests to **Application Load Balancer** (or API Gateway on port 4004 for Docker)
2. **API Gateway** validates JWT tokens with **Auth Service** and routes requests
3. **Patient Service** handles patient CRUD operations and stores data in **PatientDB** (RDS)
4. When a patient is created, **Patient Service**:
   - Calls **Billing Service** via **gRPC** to create a billing account
   - Publishes a patient event to **MSK Kafka** (or Apache Kafka for Docker)
5. **Analytics Service** consumes patient events from **Kafka** for processing

## 🔧 Services

### API Gateway (Port 4004)
- Routes incoming requests to appropriate microservices
- Handles authentication token validation via Auth Service
- Built with Spring Cloud Gateway

### Auth Service (Port 4005)
- User authentication and authorization
- JWT token generation and validation
- User management with Spring Security
- PostgreSQL database for user storage

### Patient Service (Port 4000)
- Core patient CRUD operations
- gRPC client for Billing Service communication
- Kafka producer for event publishing
- PostgreSQL database for patient data

### Billing Service (Port 4002, gRPC: 9001)
- Billing account management
- gRPC server for inter-service communication
- Creates billing accounts when patients are registered

### Analytics Service (Port 4003)
- Consumes patient events via Kafka
- Processes and analyzes patient data
- Event-driven architecture with Protobuf serialization

## 🛠️ Technology Stack

| Category | Technology |
|----------|------------|
| **Language** | Java 21 |
| **Framework** | Spring Boot 4.x |
| **API Gateway** | Spring Cloud Gateway |
| **Security** | Spring Security, JWT (jjwt 0.12.6) |
| **Database** | PostgreSQL 16 |
| **ORM** | Spring Data JPA |
| **Messaging** | Apache Kafka |
| **RPC** | gRPC with Protobuf |
| **Build Tool** | Maven |
| **Containerization** | Docker, Docker Compose |
| **Cloud Infrastructure** | AWS CDK, LocalStack |
| **Testing** | JUnit 6, REST Assured |

## 📦 Prerequisites

- **Java 21** or higher
- **Docker** and **Docker Compose**
- **Maven 3.8+**
- **LocalStack** (for local AWS infrastructure testing)

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone <repository-url>
cd Patient-Management-Final
```

### 2. Build All Services

```bash
# Build each service
cd patient-service && ./mvnw clean package -DskipTests && cd ..
cd auth-service && ./mvnw clean package -DskipTests && cd ..
cd billing-service && ./mvnw clean package -DskipTests && cd ..
cd analytics-service && ./mvnw clean package -DskipTests && cd ..
cd api-gateway && ./mvnw clean package -DskipTests && cd ..
```

### 3. Start All Services with Docker Compose

```bash
docker-compose up --build
```

This will start:
- PostgreSQL databases (PatientDB on port 5000, AuthDB on port 5001)
- Apache Kafka (ports 9092, 9094)
- All microservices

### 4. Verify Services are Running

```bash
docker ps
```

## 📡 API Endpoints

All API requests go through the **API Gateway** at `http://localhost:4004`

### Authentication

#### Login
```http
POST http://localhost:4004/auth/login
Content-Type: application/json

{
    "email": "testuser@test.com",
    "password": "password123"
}
```

#### Validate Token
```http
GET http://localhost:4004/auth/validate
Authorization: Bearer <token>
```

### Patient Management

#### Create Patient
```http
POST http://localhost:4004/api/patients
Content-Type: application/json

{
  "name": "John Doe",
  "email": "john.doe@example.com",
  "address": "123 Main St, Frankfurt, Germany",
  "birthDate": "1990-01-15",
  "registrationDate": "2025-01-01"
}
```

#### Get All Patients
```http
GET http://localhost:4004/api/patients
Authorization: Bearer <token>
```

#### Update Patient
```http
PUT http://localhost:4004/api/patients/{id}
Content-Type: application/json
Authorization: Bearer <token>

{
  "name": "John Doe Updated",
  "email": "john.updated@example.com",
  "address": "456 New St, Frankfurt, Germany"
}
```

### Billing (gRPC)

#### Create Billing Account
```http
GRPC localhost:9001/BillingService/CreateBillingAccount

{
  "patientId": "12345",
  "name": "John Doe",
  "email": "john@example.com"
}
```

## 🔄 Service Communication

### REST (HTTP)
- Client → API Gateway → Auth Service (authentication)
- Client → API Gateway → Patient Service (patient operations)

### gRPC
- Patient Service → Billing Service (billing account creation)
- Uses Protocol Buffers for efficient serialization

### Kafka (Event-Driven)
- Patient Service → Kafka → Analytics Service
- Events are published when patients are created/updated
- Uses Protobuf for message serialization

## ☁️ Infrastructure

The project uses **AWS CDK** to define infrastructure as code, deployable to **LocalStack** for local testing or **AWS** for production.

### AWS Resources Created

| Resource | Type | Details |
|----------|------|---------|
| **PatientManagementVPC** | VPC | 2 Availability Zones, public/private subnets |
| **EcsCluster** | ECS Cluster | With Cloud Map namespace `patient-management.local` |
| **API Gateway** | ECS Fargate + ALB | 256 CPU, 512MB, with Application Load Balancer |
| **Auth Service** | ECS Fargate | 256 CPU, 512MB, depends on AuthDB |
| **Patient Service** | ECS Fargate | 256 CPU, 512MB, depends on PatientDB, Billing, MSK |
| **Billing Service** | ECS Fargate | 256 CPU, 512MB, gRPC on port 9001 |
| **Analytics Service** | ECS Fargate | 256 CPU, 512MB, depends on MSK |
| **AuthServiceDB** | RDS PostgreSQL 16.3 | t2.micro, 20GB storage |
| **PatientServiceDB** | RDS PostgreSQL 16.3 | t2.micro, 20GB storage |
| **MskCluster** | MSK Kafka 3.5.1 | 2 broker nodes (kafka.m5.large) |

### LocalStack Deployment

```bash
# 1. Start LocalStack
docker run -d --name localstack -p 4566:4566 localstack/localstack

# 2. Build all Docker images
docker-compose build

# 3. Generate CloudFormation template
cd infrastructure
mvn compile exec:java

# 4. Deploy to LocalStack
./localstack-deploy.sh

# 5. Get the Load Balancer DNS
aws --endpoint-url=http://localhost:4566 elbv2 describe-load-balancers \
    --query "LoadBalancers[0].DNSName" --output text
```

### Access via LocalStack
After deployment, access the API through the Load Balancer DNS:
```
http://lb-XXXXXXXX.elb.localhost.localstack.cloud:4004/api/patients
```

### AWS Production Deployment

```bash
# 1. Configure AWS credentials
aws configure

# 2. Bootstrap CDK (first time only)
cdk bootstrap

# 3. Deploy to AWS
cd infrastructure
cdk deploy
```

## 🧪 Testing

### Unit Tests

```bash
# Run tests for individual services
cd patient-service && ./mvnw test
cd auth-service && ./mvnw test
cd billing-service && ./mvnw test
cd analytics-service && ./mvnw test
```

### Integration Tests

```bash
cd Integration-tests
mvn test
```

Integration tests use:
- **REST Assured** for API testing
- **JUnit 6** for test execution

### HTTP Request Files

The project includes `.http` files for testing APIs:
- `api-requests/auth-service/` - Authentication requests
- `api-requests/patients-service/` - Patient CRUD requests
- `grpc-requests/billing-service/` - gRPC billing requests

## 📁 Project Structure

```
Patient-Management-Final/
├── api-gateway/              # Spring Cloud Gateway
├── auth-service/             # Authentication microservice
├── patient-service/          # Patient management microservice
├── billing-service/          # Billing microservice (gRPC server)
├── analytics-service/        # Analytics microservice (Kafka consumer)
├── infrastructure/           # AWS CDK/LocalStack templates
├── Integration-tests/        # End-to-end integration tests
├── api-requests/             # HTTP request files for testing
│   ├── auth-service/
│   └── patients-service/
├── grpc-requests/            # gRPC request files for testing
│   └── billing-service/
└── docker-compose.yml        # Docker Compose configuration
```

## 🔐 Environment Variables and Configuration

Each service keeps its own `application.yml`. These files
contain service-specific behavior and safe defaults for running a service directly.
They do not contain passwords or JWT signing keys.

For local Docker Compose development, create your own root `.env` file. Docker Compose
reads it for variable substitution and passes only the relevant values to each service.
Choose your own database names, usernames, passwords, and JWT secret:

```dotenv
PATIENT_DB_NAME=your_patient_database
PATIENT_DB_USER=your_patient_database_user
PATIENT_DB_PASSWORD=your_patient_database_password

AUTH_DB_NAME=your_auth_database
AUTH_DB_USER=your_auth_database_user
AUTH_DB_PASSWORD=your_auth_database_password

JWT_SECRET=your_long_random_jwt_secret
```

Then start the application:

```bash
docker compose up --build
```

`.env` is ignored by Git and must not be committed. The variables required in it are:

| Variable | Used by | Description |
|----------|---------|-------------|
| `PATIENT_DB_NAME` | PostgreSQL and patient service | Patient database name |
| `PATIENT_DB_USER` | PostgreSQL and patient service | Patient database user |
| `PATIENT_DB_PASSWORD` | PostgreSQL and patient service | Patient database password |
| `AUTH_DB_NAME` | PostgreSQL and auth service | Auth database name |
| `AUTH_DB_USER` | PostgreSQL and auth service | Auth database user |
| `AUTH_DB_PASSWORD` | PostgreSQL and auth service | Auth database password |
| `JWT_SECRET` | Auth service | JWT signing key |

Compose also supplies non-secret Docker network addresses:

| Variable | Service | Value in Compose |
|----------|---------|------------------|
| `BILLING_SERVICE_ADDRESS` | Patient | `billing-service` |
| `BILLING_SERVICE_PORT` | Patient | `9001` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Patient, analytics | `kafka:9092` |
| `AUTH_SERVICE_URL` | API gateway | `http://auth-service:4005` |
| `PATIENT_SERVICE_URL` | API gateway | `http://patient-service:4000` |

For staging or production, inject these values through the deployment platform
(for example AWS Secrets Manager/ECS task-definition secrets, Kubernetes Secrets, or
Vault) instead of committing or distributing a production `.env` file.

## 🗃️ Database Configuration

Docker Compose creates two PostgreSQL databases using the values you choose in `.env`:

### Patient Database
- **Container**: `patient-postgres`
- **Port**: `5000` (mapped to internal `5432`)
- **Database, user, and password**: configured with `PATIENT_DB_NAME`, `PATIENT_DB_USER`, and `PATIENT_DB_PASSWORD`

### Auth Database
- **Container**: `auth-postgres`
- **Port**: `5001` (mapped to internal `5432`)
- **Database, user, and password**: configured with `AUTH_DB_NAME`, `AUTH_DB_USER`, and `AUTH_DB_PASSWORD`

The database values are applied when each PostgreSQL data volume is initialized. If you
change them after the containers have already been started, recreate the volumes with
`docker compose down -v` before running `docker compose up --build` again. This removes
the existing local database data.

## 📝 License

This project is licensed under the MIT License.

## 👥 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
