package ai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRedact(t *testing.T) {
	in := "PAN ABCDE1234F, Aadhaar 1234 5678 9012, card 4111 1111 1111 1111, A/c 123456789012, " +
		"IFSC SBIN0001234, mob +91 9876543210, mail vishwas@example.com, UPI abc@ybl, your OTP is 482913, NIFTY 25044.65"
	out := Redact(in)
	for _, secret := range []string{"ABCDE1234F", "5678 9012", "4111", "123456789012", "SBIN0001234", "9876543210", "vishwas@example.com", "abc@ybl", "482913"} {
		if strings.Contains(out, secret) {
			t.Errorf("%q not hidden in %q", secret, out)
		}
	}
	if !strings.Contains(out, "25044.65") {
		t.Errorf("market price hidden by mistake: %q", out)
	}
}

func TestPickGeminiModel(t *testing.T) {
	names := []string{"models/gemini-2.0-flash", "models/gemini-2.5-flash", "models/gemini-2.5-flash-lite", "models/gemini-2.5-pro", "models/gemini-3-flash", "models/gemini-2.5-flash-preview-tts"}
	if got := PickGeminiModel(names); got != "gemini-3-flash" {
		t.Fatalf("got %s", got)
	}
	if PickGeminiModel(nil) != DefaultGeminiModel {
		t.Fatal("fallback")
	}
}

func TestPromptLimit(t *testing.T) {
	p := Prompt(Summary, "t", strings.Repeat("a", MaxPageChars+500), "")
	if strings.Count(p, "a") > MaxPageChars+50 {
		t.Fatal("page text not cut")
	}
	if !strings.Contains(Prompt(Ask, "t", "x", "kya hai?"), "kya hai?") {
		t.Fatal("question missing")
	}
}

func TestCost(t *testing.T) {
	c := ClaudeCostRupees(3000, 800) // about ₹0.63
	if c < 0.5 || c > 0.8 {
		t.Fatalf("cost %v", c)
	}
}

func TestOpenAICompatible(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer k" || !strings.HasSuffix(r.URL.Path, "/chat/completions") {
			w.WriteHeader(401)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": "नमस्ते"}}}})
	}))
	defer srv.Close()
	httpClient = srv.Client()
	r, err := Run(context.Background(), Provider{Kind: "custom", Key: "k", Model: "m", BaseURL: srv.URL + "/v1"}, "hi")
	if err != nil || r.Text != "नमस्ते" {
		t.Fatalf("%v %q", err, r.Text)
	}
	if _, err := Run(context.Background(), Provider{Kind: "gemini"}, "x"); err != ErrNoKey {
		t.Fatal("missing key not reported")
	}
}

func TestGeminiParse(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("x-goog-api-key") != "g" {
			w.WriteHeader(403)
			return
		}
		w.Write([]byte(`{"candidates":[{"content":{"parts":[{"text":"सार"},{"text":" यहाँ"}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}`))
	}))
	defer srv.Close()
	httpClient = srv.Client()
	old := geminiBaseURL
	geminiBaseURL = srv.URL + "/"
	defer func() { geminiBaseURL = old }()
	r, err := Run(context.Background(), Provider{Kind: "gemini", Key: "g", Model: "m"}, "x")
	if err != nil || r.Text != "सार यहाँ" {
		t.Fatalf("%v %q", err, r.Text)
	}
	if _, err := Run(context.Background(), Provider{Kind: "gemini", Key: "bad", Model: "m"}, "x"); err == nil || !strings.Contains(err.Error(), "key") {
		t.Fatalf("bad key error: %v", err)
	}
}

func TestGeminiCandidatesAndBusy(t *testing.T) {
	got := GeminiCandidates([]string{"models/gemini-2.5-flash", "models/gemini-3.0-flash-lite", "models/gemini-3.0-flash",
		"models/gemini-2.5-pro", "models/gemini-2.5-flash-lite", "models/gemini-2.0-flash", "models/gemini-3.0-flash-preview-x"})
	if strings.Join(got, ",") != "gemini-3.0-flash,gemini-2.5-flash,gemini-2.0-flash,gemini-3.0-flash-lite" {
		t.Fatal(got)
	}
	if len(GeminiCandidates(nil)) == 0 {
		t.Fatal("no default")
	}
	if !IsBusy(friendlyHTTP(503, nil)) || !IsBusy(friendlyHTTP(404, nil)) || IsBusy(friendlyHTTP(401, nil)) || IsBusy(friendlyHTTP(429, nil)) {
		t.Fatal("IsBusy wrong")
	}
}
