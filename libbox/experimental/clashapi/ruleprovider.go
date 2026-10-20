package clashapi

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/render"
)

func ruleProviderRouter() http.Handler {
	r := chi.NewRouter()
	r.Get("/", getRuleProviders)

	r.Route("/{name}", func(r chi.Router) {
		r.Use(parseProviderName, findRuleProviderByName)
		r.Get("/", getRuleProvider)
		r.Put("/", updateRuleProvider)
	})
	return r
}

func getRuleProviders(w http.ResponseWriter, r *http.Request) {
	render.JSON(w, r, render.M{
		"providers": []string{},
	})
}

func getRuleProvider(w http.ResponseWriter, r *http.Request) {
	render.NoContent(w, r)
}

func updateRuleProvider(w http.ResponseWriter, r *http.Request) {
	render.NoContent(w, r)
}

func findRuleProviderByName(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		render.Status(r, http.StatusNotFound)
		render.JSON(w, r, ErrNotFound)
	})
}
