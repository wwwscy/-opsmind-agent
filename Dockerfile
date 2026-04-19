FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# 复制 Maven 相关文件
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# 设置权限并下载依赖
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# 复制源代码
COPY src ./src

# 编译
RUN ./mvnw package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 复制编译好的 jar
COPY --from=builder /app/target/*.jar app.jar

# 复制配置文件（挂载方式更灵活）
# COPY entrypoint.sh /entrypoint.sh
# RUN chmod +x /entrypoint.sh

EXPOSE 8080

# 使用挂载的配置文件
# ENTRYPOINT ["/entrypoint.sh"]
ENTRYPOINT ["java", "-jar", "app.jar"]
