# Spring Boot & Java Backend Standards

## 1. Imports vs. Fully Qualified Names
- **Rule:** Never use fully qualified class names inline in the code (e.g., `java.util.Optional<com.studentprep.student.Student>`).
- **Implementation:** Always add proper `import` statements at the top of the file and use the short class name in the code (e.g., `Optional<Student>`). This keeps the code clean, readable, and idiomatic.

## 2. Dependency Injection
- Always use constructor injection instead of `@Autowired` field injection for better testability and immutability.
