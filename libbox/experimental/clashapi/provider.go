package clashapi

import (
	"context"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/render"
)

func proxyProviderRouter() http.Handler {
	r := chi.NewRouter()
	r.Get("/", getProviders)

	r.Route("/{name}", func(r chi.Router) {
		r.Use(parseProviderName, findProviderByName)
		r.Get("/", getProvider)
		r.Put("/", updateProvider)
		r.Get("/healthcheck", healthCheckProvider)
	})
	return r
}

func getProviders(w http.ResponseWriter, r *http.Request) {
	render.JSON(w, r, render.M{
		"providers": render.M{},
	})
}

func getProvider(w http.ResponseWriter, r *http.Request) {
	render.NoContent(w, r)
}

func updateProvider(w http.ResponseWriter, r *http.Request) {
	render.NoContent(w, r)
}

func healthCheckProvider(w http.ResponseWriter, r *http.Request) {
	render.NoContent(w, r)
}

func parseProviderName(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		name := getEscapeParam(r, "name")
		ctx := context.WithValue(r.Context(), CtxKeyProviderName, name)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func findProviderByName(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		render.Status(r, http.StatusNotFound)
		render.JSON(w, r, ErrNotFound)

	})
}
