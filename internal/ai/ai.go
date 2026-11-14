// Package ai is Ronin's AI Gateway: it hides private numbers before any
// text leaves the computer, builds the requests for Gemini (free), Claude
// (paid, off by default) or any OpenAI-compatible service, and reads the
// answers.
package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"time"
)

// MaxPageChars is how much page text is ever sent.
const MaxPageChars = 12000

var redactions = []struct {
	re   *regexp.Regexp
	with string
}{
	{regexp.MustCompile(`(?i)\b[\w.+-]+@[\w-]+\.[\w.-]+\b`), "[email hidden]"},
	{regexp.MustCompile(`(?i)\b[\w.-]{2,}@(?:ybl|ibl|axl|paytm|okaxis|oksbi|okhdfcbank|okicici|upi|apl|yapl|fbl|sbi|icici|hdfcbank|axisbank|kotak|pnb|boi|barodampay|valid)\b`), "[UPI ID hidden]"},
	{regexp.MustCompile(`(?i)\b(otp|one[- ]time password|pin|cvv|password|passcode)\b[^0-9\n]{0,20}\d{3,8}\b`), "$1 [hidden]"},
	{regexp.MustCompile(`\b[A-Z]{5}[0-9]{4}[A-Z]\b`), "[PAN hidden]"},
	{regexp.MustCompile(`\b[A-Z]{4}0[A-Z0-9]{6}\b`), "[IFSC hidden]"},
	{regexp.MustCompile(`\b(?:\d[ -]?){12,18}\d\b`), "[number hidden]"}, // cards, Aadhaar, account numbers
	{regexp.MustCompile(`\b\d{4}\s\d{4}\s\d{4}\b`), "[Aadhaar hidden]"},
	{regexp.MustCompile(`(?:\+91[\s-]?|\b0)?\b[6-9]\d{9}\b`), "[phone hidden]"},
	{regexp.MustCompile(`\b\d{9,18}\b`), "[number hidden]"},
}

// Redact hides personal numbers and IDs in text.
func Redact(s string) string {
	for _, r := range redactions {
		s = r.re.ReplaceAllString(s, r.with)
	}
	return s
}

// Task is what the user asked for.
type Task string

const (
	Summary   Task = "summary"
	Explain   Task = "explain" // explain in simple Hindi
	Translate Task = "translate"
	Ask       Task = "ask"
	ScamCheck Task = "scam"
)

// Prompt builds the text sent to the AI. pageText must already be redacted.
func Prompt(t Task, title, pageText, question string) string {
	if len(pageText) > MaxPageChars {
		pageText = pageText[:MaxPageChars]
	}
	head := "Page title: " + title + "\n\nPage text:\n\"\"\"\n" + pageText + "\n\"\"\"\n\n"
	switch t {
	case Summary:
		return head + "Summarise this page in simple Hindi (Devanagari) in 5 to 8 short bullet points. Keep English technical words as they are."
	case Explain:
		return head + "Explain this page in very simple Hindi (Devanagari), as you would to a primary school teacher who is new to the topic. Use short paragraphs."
	case Translate:
		return head + "Translate the page text into natural Hindi (Devanagari). Keep names, numbers and English technical terms. Do not add anything."
	case ScamCheck:
		return head + "You are a cyber-fraud expert in India. Is this page or chat a scam (investment scam, fake profit, fee before withdrawal, impersonation, fake customer care, phishing)? " +
			"Answer in simple Hindi (Devanagari). First line exactly one of: 'सुरक्षित लगता है', 'संदिग्ध', 'खतरनाक - scam'. Then 3 to 5 short reasons, then what the person should do. " +
			"Mention the 1930 helpline and cybercrime.gov.in if money was asked for."
	default:
		return head + "Answer this question about the page in simple Hindi (Devanagari), using only the page text when possible:\n" + question
	}
}

// Provider describes one AI service.
type Provider struct {
	Kind    string // gemini, claude, custom
	Key     string
	Model   string
	BaseURL string // custom only
}

// Result is an answer and, for paid services, its approximate cost.
type Result struct {
	Text      string
	InTokens  int
	OutTokens int
}

var httpClient = &http.Client{Timeout: 90 * time.Second}

// ErrNoKey means the user has not added a key yet.
var ErrNoKey = errors.New("no AI key added yet")

// Run sends the prompt and returns the answer.
func Run(ctx context.Context, p Provider, prompt string) (Result, error) {
	if strings.TrimSpace(p.Key) == "" {
		return Result{}, ErrNoKey
	}
	switch p.Kind {
	case "claude":
		return runClaude(ctx, p, prompt)
	case "custom":
		return runOpenAI(ctx, p, prompt)
	default:
		return runGemini(ctx, p, prompt)
	}
}

func post(ctx context.Context, url string, headers map[string]string, body any, out any) error {
	b, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return errors.New("no internet connection, or the AI service did not answer")
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode != 200 {
		return friendlyHTTP(resp.StatusCode, data)
	}
	return json.Unmarshal(data, out)
}

// busyError means the AI service is overloaded for a moment (another try,
// or another model, usually works).
type busyError struct{ msg string }

func (e busyError) Error() string { return e.msg }

// IsBusy reports a "try again" kind of failure: the service is overloaded,
// or this model is not available.
func IsBusy(err error) bool {
	var b busyError
	return errors.As(err, &b)
}

func friendlyHTTP(code int, body []byte) error {
	switch code {
	case 500, 502, 503, 504:
		return busyError{"the free AI service is very busy right now; please try again in a minute"}
	case 400:
		if bytes.Contains(bytes.ToLower(body), []byte("api key")) {
			return errors.New("the AI key is not valid; please check it in Settings")
		}
		return errors.New("the AI service could not read this page")
	case 401, 403:
		return errors.New("the AI key is not valid or not allowed; please check it in Settings")
	case 429:
		return errors.New("the free daily limit of this AI key is used up; try again later")
	case 404:
		return busyError{"this AI model is not available; Ronin will choose another one"}
	}
	return fmt.Errorf("the AI service had a problem (%d); try again later", code)
}

// ---- Gemini --------------------------------------------------------------------

var geminiBaseURL = "https://generativelanguage.googleapis.com/v1beta/"

// DefaultGeminiModel is used if the model list cannot be read.
const DefaultGeminiModel = "gemini-2.5-flash"

// PickGeminiModel chooses the newest general "flash" model from a model list.
func PickGeminiModel(names []string) string {
	type cand struct {
		name string
		ver  []int
	}
	verRe := regexp.MustCompile(`gemini-(\d+)(?:\.(\d+))?-flash$`)
	var cs []cand
	for _, n := range names {
		n = strings.TrimPrefix(n, "models/")
		m := verRe.FindStringSubmatch(n)
		if m == nil {
			continue
		}
		major, minor := atoi(m[1]), atoi(m[2])
		cs = append(cs, cand{n, []int{major, minor}})
	}
	if len(cs) == 0 {
		return DefaultGeminiModel
	}
	sort.Slice(cs, func(i, j int) bool {
		if cs[i].ver[0] != cs[j].ver[0] {
			return cs[i].ver[0] > cs[j].ver[0]
		}
		return cs[i].ver[1] > cs[j].ver[1]
	})
	return cs[0].name
}

// GeminiCandidates lists the models to try, best first: the newest "flash"
// models, then the lighter "flash-lite" ones (at most 4).
func GeminiCandidates(names []string) []string {
	type cand struct {
		name       string
		lite       bool
		maj, minor int
	}
	re := regexp.MustCompile(`^gemini-(\d+)(?:\.(\d+))?-flash(-lite)?$`)
	var cs []cand
	for _, n := range names {
		n = strings.TrimPrefix(n, "models/")
		if m := re.FindStringSubmatch(n); m != nil {
			cs = append(cs, cand{n, m[3] != "", atoi(m[1]), atoi(m[2])})
		}
	}
	sort.SliceStable(cs, func(i, j int) bool {
		if cs[i].lite != cs[j].lite {
			return !cs[i].lite
		}
		if cs[i].maj != cs[j].maj {
			return cs[i].maj > cs[j].maj
		}
		return cs[i].minor > cs[j].minor
	})
	var out []string
	for _, c := range cs {
		out = append(out, c.name)
		if len(out) == 4 {
			break
		}
	}
	if len(out) == 0 {
		out = []string{DefaultGeminiModel, "gemini-2.5-flash-lite"}
	}
	return out
}

func atoi(s string) int {
	n := 0
	for _, r := range s {
		if r >= '0' && r <= '9' {
			n = n*10 + int(r-'0')
		}
	}
	return n
}

// GeminiModels lists the model names available to a key.
func GeminiModels(ctx context.Context, key string) ([]string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, geminiBaseURL+"models?pageSize=200", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("x-goog-api-key", key)
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, errors.New("no internet connection")
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode != 200 {
		return nil, friendlyHTTP(resp.StatusCode, data)
	}
	var out struct {
		Models []struct {
			Name    string   `json:"name"`
			Methods []string `json:"supportedGenerationMethods"`
		} `json:"models"`
	}
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, err
	}
	var names []string
	for _, m := range out.Models {
		for _, x := range m.Methods {
			if x == "generateContent" {
				names = append(names, m.Name)
			}
		}
	}
	return names, nil
}

func runGemini(ctx context.Context, p Provider, prompt string) (Result, error) {
	model := p.Model
	if model == "" {
		model = DefaultGeminiModel
	}
	body := map[string]any{
		"contents":         []any{map[string]any{"role": "user", "parts": []any{map[string]string{"text": prompt}}}},
		"generationConfig": map[string]any{"temperature": 0.3, "maxOutputTokens": 2048},
	}
	var out struct {
		Candidates []struct {
			Content struct {
				Parts []struct {
					Text string `json:"text"`
				} `json:"parts"`
			} `json:"content"`
			FinishReason string `json:"finishReason"`
		} `json:"candidates"`
		Usage struct {
			In  int `json:"promptTokenCount"`
			Out int `json:"candidatesTokenCount"`
		} `json:"usageMetadata"`
	}
	err := post(ctx, geminiBaseURL+"models/"+model+":generateContent", map[string]string{"x-goog-api-key": p.Key}, body, &out)
	if err != nil {
		return Result{}, err
	}
	var sb strings.Builder
	for _, c := range out.Candidates {
		for _, part := range c.Content.Parts {
			sb.WriteString(part.Text)
		}
		break
	}
	if strings.TrimSpace(sb.String()) == "" {
		return Result{}, errors.New("the AI gave no answer for this page")
	}
	return Result{Text: sb.String(), InTokens: out.Usage.In, OutTokens: out.Usage.Out}, nil
}

// ---- Claude (paid) -----------------------------------------------------------

// DefaultClaudeModel is the cheapest current Claude model.
const DefaultClaudeModel = "claude-haiku-4-5"

// ClaudeCostRupees estimates the price of one request (Haiku 4.5: $1 per
// million input tokens, $5 per million output tokens; about ₹90 per dollar).
func ClaudeCostRupees(inTokens, outTokens int) float64 {
	return (float64(inTokens)*1 + float64(outTokens)*5) / 1e6 * 90
}

// EstimateTokens is a rough count used before sending (about 3 characters a token).
func EstimateTokens(s string) int { return len([]rune(s))/3 + 1 }

func runClaude(ctx context.Context, p Provider, prompt string) (Result, error) {
	model := p.Model
	if model == "" {
		model = DefaultClaudeModel
	}
	body := map[string]any{
		"model": model, "max_tokens": 1500,
		"messages": []any{map[string]string{"role": "user", "content": prompt}},
	}
	var out struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
		Usage struct {
			In  int `json:"input_tokens"`
			Out int `json:"output_tokens"`
		} `json:"usage"`
	}
	err := post(ctx, "https://api.anthropic.com/v1/messages",
		map[string]string{"x-api-key": p.Key, "anthropic-version": "2023-06-01"}, body, &out)
	if err != nil {
		return Result{}, err
	}
	var sb strings.Builder
	for _, c := range out.Content {
		if c.Type == "text" {
			sb.WriteString(c.Text)
		}
	}
	return Result{Text: sb.String(), InTokens: out.Usage.In, OutTokens: out.Usage.Out}, nil
}

// ---- any OpenAI-compatible service -----------------------------------------------

func runOpenAI(ctx context.Context, p Provider, prompt string) (Result, error) {
	if !strings.HasPrefix(p.BaseURL, "https://") || p.Model == "" {
		return Result{}, errors.New("add the service address (https://...) and model name in Settings")
	}
	body := map[string]any{
		"model": p.Model, "temperature": 0.3,
		"messages": []any{map[string]string{"role": "user", "content": prompt}},
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	url := strings.TrimRight(p.BaseURL, "/") + "/chat/completions"
	if err := post(ctx, url, map[string]string{"Authorization": "Bearer " + p.Key}, body, &out); err != nil {
		return Result{}, err
	}
	if len(out.Choices) == 0 {
		return Result{}, errors.New("the AI gave no answer")
	}
	return Result{Text: out.Choices[0].Message.Content}, nil
}
