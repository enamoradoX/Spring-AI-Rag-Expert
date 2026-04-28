import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ChatAnalyticsOverview {
  totalCalls: number;
  successfulCalls: number;
  failedCalls: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
  totalEstimatedCostUsd: number;
  averageLatencyMs: number;
}

export interface ChatAnalyticsOperationSummary {
  operation: string;
  callCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  estimatedCostUsd: number;
  averageLatencyMs: number;
}

export interface ChatAnalyticsModelSummary {
  provider: string;
  model: string;
  callCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  estimatedCostUsd: number;
  averageLatencyMs: number;
}

export interface ChatUsageEvent {
  timestamp: string;
  provider: string;
  model: string;
  operation: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  latencyMs: number;
  estimatedCostUsd: number;
  success: boolean;
  finishReason: string | null;
  requestId: string | null;
}

export interface ChatAnalyticsSummaryResponse {
  overview: ChatAnalyticsOverview;
  byOperation: ChatAnalyticsOperationSummary[];
  byModel: ChatAnalyticsModelSummary[];
  recentCalls: ChatUsageEvent[];
}

@Injectable({
  providedIn: 'root'
})
export class AnalyticsService {
  private apiUrl = 'http://localhost:8080/api/analytics/chat';

  constructor(private http: HttpClient) { }

  getChatAnalytics(): Observable<ChatAnalyticsSummaryResponse> {
    return this.http.get<ChatAnalyticsSummaryResponse>(this.apiUrl);
  }
}

