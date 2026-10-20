package clashapi

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/render"
)

func profileRouter() http.Handler {
	r := chi.NewRouter()
	r.Get("/tracing", subscribeTracing)
	return r
}

func subscribeTracing(w http.ResponseWriter, r *http.Request) {
	render.Status(r, http.StatusNotFound)
	render.JSON(w, r, ErrNotFound)
}
