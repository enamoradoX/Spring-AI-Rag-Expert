import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface DocumentLoadResponse {
  message: string;
  success: boolean;
}

export interface DocumentFileType {
  type: 'pdf' | 'docx' | 'text';
  mimeType: string;
}

export interface S3Config {
  bucket: string;
  endpoint: string;
  region: string;
  status: string;
}

@Injectable({
  providedIn: 'root'
})
export class DocumentService {
  private apiUrl = 'http://localhost:8080/api/documents';
  private s3ApiUrl = 'http://localhost:8080/api/s3';

  constructor(private http: HttpClient) { }

  loadDocument(documentUrl: string): Observable<DocumentLoadResponse> {
    return this.http.post<DocumentLoadResponse>(`${this.apiUrl}/load-single`, null, {
      params: { url: documentUrl }
    });
  }

  uploadDocumentFile(file: File): Observable<DocumentLoadResponse> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    return this.http.post<DocumentLoadResponse>(`${this.apiUrl}/upload`, formData);
  }

  loadFromS3(key: string): Observable<DocumentLoadResponse> {
    return this.http.post<DocumentLoadResponse>(`${this.s3ApiUrl}/load`, { key });
  }

  getS3Config(): Observable<S3Config> {
    return this.http.get<S3Config>(`${this.s3ApiUrl}/config`);
  }

  getDocuments(): Observable<string[]> {
    return this.http.get<string[]>(this.apiUrl);
  }

  deleteDocument(documentUrl: string): Observable<DocumentLoadResponse> {
    return this.http.delete<DocumentLoadResponse>(this.apiUrl, {
      params: { url: documentUrl }
    });
  }

  getDocumentContent(documentUrl: string): Observable<string> {
    return this.http.get(`${this.apiUrl}/content`, {
      params: { url: documentUrl },
      responseType: 'text'
    });
  }

  getRawDocumentUrl(documentUrl: string): string {
    return `${this.apiUrl}/raw?url=${encodeURIComponent(documentUrl)}`;
  }

  getFileType(documentUrl: string): Observable<DocumentFileType> {
    return this.http.get<DocumentFileType>(`${this.apiUrl}/filetype`, {
      params: { url: documentUrl }
    });
  }
}

