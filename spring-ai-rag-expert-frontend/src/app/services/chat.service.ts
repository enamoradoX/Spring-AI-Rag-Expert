import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Question {
  question: string;
}

export interface Answer {
  answer: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private apiUrl = 'http://localhost:8080';

  constructor(private http: HttpClient) { }

  askQuestion(question: string): Observable<Answer> {
    const payload: Question = { question };
    return this.http.post<Answer>(`${this.apiUrl}/ask`, payload);
  }
}

