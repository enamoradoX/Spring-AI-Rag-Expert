# Spring AI RAG Expert - Quick Start Guide

## 🎉 Your Angular Frontend is Ready!

### What You Have Now:

✅ **Chat Interface** - Ask questions about your documents  
✅ **Document Upload** - Load documents via URL  
✅ **Real-time Responses** - Streaming answers from AI  
✅ **Modern UI** - Beautiful, responsive design  
✅ **CORS Enabled** - Backend configured for frontend calls  

---

## 🚀 How to Run

### 1. Start Docker Services (Milvus Vector DB)
```powershell
cd C:\Git3\spring-ai-rag-expert
docker-compose up -d
```

### 2. Start Backend (Spring Boot)
```powershell
# Set OpenAI API Key
$env:OPENAI_API_KEY="sk-your-api-key-here"

# Navigate to backend
cd spring-ai-rag-expert-backend

# Run Spring Boot
./mvnw spring-boot:run
```

Backend will run on: **http://localhost:8080**

### 3. Start Frontend (Angular)
```powershell
# Open new terminal
cd C:\Git3\spring-ai-rag-expert\spring-ai-rag-expert-frontend

# Start Angular dev server
ng serve
```

Frontend will run on: **http://localhost:4200**

---

## 🎯 How to Use

### Chat with Your Documents
1. Open http://localhost:4200
2. Type your question in the input box
3. Press Enter or click "📤 Send"
4. Wait for AI response with context from your documents

### Upload New Documents
1. Click "📄 Upload Document" button
2. Enter document URL:
   - PDF: `https://example.com/document.pdf`
   - TXT: `https://example.com/notes.txt`
   - DOCX: `https://example.com/report.docx`
3. Click "Load Document"
4. Document will be embedded and ready for questions!

---

## 📁 Project Structure

```
spring-ai-rag-expert/
├── spring-ai-rag-expert-backend/    # Java/Spring Boot API
│   ├── src/main/java/
│   │   └── guru/springframework/springairagexpert/
│   │       ├── config/
│   │       │   └── CorsConfig.java          ✅ NEW!
│   │       ├── controllers/
│   │       │   ├── QuestionController.java  (/ask endpoint)
│   │       │   └── DocumentLoaderController.java  (/api/documents/load-single)
│   │       ├── services/
│   │       └── model/
│   └── pom.xml
│
├── spring-ai-rag-expert-frontend/   # Angular UI
│   ├── src/app/
│   │   ├── services/
│   │   │   ├── chat.service.ts     ✅ NEW!
│   │   │   └── document.service.ts ✅ NEW!
│   │   ├── app.component.ts        ✅ UPDATED!
│   │   ├── app.component.html      ✅ UPDATED!
│   │   ├── app.component.css       ✅ UPDATED!
│   │   └── app.module.ts           ✅ UPDATED!
│   └── package.json
│
├── docker-compose.yml              # Milvus vector database
├── volumes/                        # Docker data (gitignored)
└── .gitignore
```

---

## 🔌 API Endpoints

### Backend APIs (Port 8080)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/ask` | Ask a question about documents |
| POST | `/api/documents/load-single?url=...` | Load a document from URL |

### Example cURL:
```bash
# Ask question
curl -X POST http://localhost:8080/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"What is the password?"}'

# Load document
curl -X POST "http://localhost:8080/api/documents/load-single?url=https://example.com/doc.pdf"
```

---

## ⚙️ Configuration

### Backend (application.yaml)
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}  # Set via environment variable
```

### Frontend (services)
```typescript
// Default API URL in chat.service.ts and document.service.ts
private apiUrl = 'http://localhost:8080';
```

---

## 🎨 Features

### Chat Interface
- ✅ Message history
- ✅ User/Assistant message distinction
- ✅ Loading indicators
- ✅ Keyboard shortcuts (Enter to send)
- ✅ Auto-scroll to latest message
- ✅ Timestamps
- ✅ Error handling

### Document Upload
- ✅ URL-based document loading
- ✅ Success/error feedback
- ✅ Support for PDF, TXT, DOCX
- ✅ Loading states
- ✅ Toggleable interface

---

## 🐛 Troubleshooting

### Backend Not Starting
```powershell
# Check if OpenAI API key is set
echo $env:OPENAI_API_KEY

# Check if Milvus is running
docker ps | Select-String "milvus"
```

### Frontend CORS Errors
- Verify `CorsConfig.java` exists in backend
- Check backend is running on port 8080
- Restart backend after adding CORS config

### Document Loading Fails
- Ensure document URL is publicly accessible
- Check backend logs for errors
- Verify document format is supported (PDF, TXT, DOCX)

---

## 📝 Example Questions to Try

After loading documents:
- "What is the main topic of the document?"
- "Summarize the key points"
- "What is the password?" (if in JIRA doc)
- "List all the important dates mentioned"

---

## 🎯 Next Steps

### Enhancements You Can Add:
1. **File Upload** - Upload local files instead of URLs
2. **Chat History** - Save conversation history
3. **Multiple Chats** - Create separate chat sessions
4. **Document List** - Show loaded documents
5. **Markdown Support** - Render formatted responses
6. **Authentication** - Add user login
7. **Streaming** - Real-time token streaming

---

## 📚 Resources

- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)
- [Angular Documentation](https://angular.io/docs)
- [Milvus Documentation](https://milvus.io/docs)

---

**Enjoy your AI-powered document Q&A system! 🚀**

