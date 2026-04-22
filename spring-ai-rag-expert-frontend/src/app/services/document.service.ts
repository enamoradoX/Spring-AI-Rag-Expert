import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface DocumentLoadResponse {
  message: string;
  success: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class DocumentService {
  private apiUrl = 'http://localhost:8080/api/documents';

  constructor(private http: HttpClient) { }

  loadDocument(documentUrl: string): Observable<DocumentLoadResponse> {
    return this.http.post<DocumentLoadResponse>(`${this.apiUrl}/load-single`, null, {
      params: { url: documentUrl }
    });
  }

  getDocuments(): Observable<string[]> {
    return this.http.get<string[]>(this.apiUrl);
  }

  deleteDocument(documentUrl: string): Observable<DocumentLoadResponse> {
    return this.http.delete<DocumentLoadResponse>(this.apiUrl, {
      params: { url: documentUrl }
    });
  }
}

