package main

import "sync"

// The laptop speaks the language of Windows itself: English on an English Windows, Hindi on a Hindi
// one (the same rule as the phone, where the pages follow the phone's language). The pages ask
// navigator.language, which WebView2 takes from Windows, so Go and the pages always agree.

var (
	pGetUserDefaultUILanguage = kernel32.NewProc("GetUserDefaultUILanguage")
	langOnce                  sync.Once
	langHindi                 bool
)

const langHindiPrimary = 0x39 // LANG_HINDI

func isHindi() bool {
	langOnce.Do(func() {
		if r, _, _ := pGetUserDefaultUILanguage.Call(); r != 0 {
			langHindi = r&0x3ff == langHindiPrimary
		}
	})
	return langHindi
}

// L picks the sentence in the laptop's language.
func L(hi, en string) string {
	if isHindi() {
		return hi
	}
	return en
}
