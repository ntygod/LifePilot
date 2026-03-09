FROM maven:3.9-eclipse-temurin-22 AS builder
WORKDIR /build

# 预先下载依赖以利用 Docker 层缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 拷贝源码并打包
COPY src ./src
RUN mvn package -DskipTests -B
# 产出: /build/target/zhiwei.jar

FROM eclipse-temurin:22-jre-alpine
WORKDIR /app

# 仅拷贝可执行 JAR 到运行时镜像，并命名为 zhiwei.jar
COPY --from=builder /build/target/zhiwei.jar zhiwei.jar

# 数据目录挂载点
RUN mkdir -p /data
EXPOSE 8080

# JVM 默认参数，可通过 docker-compose 环境变量 JAVA_OPTS 覆盖
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar zhiwei.jar"]
