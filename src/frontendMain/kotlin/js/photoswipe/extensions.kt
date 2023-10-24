package js.photoswipe

import js.getHTMLElementOrThrow
import kotlinext.js.jsObject
import io.kvision.html.*


class PhotoSwipeElem : Div(classes = setOf("pswp")) {

	// init the DOM elements required by photoswipe
	// see: https://photoswipe.com/documentation/getting-started.html
	init {
		setAttribute("tabindex", "-1")
		setAttribute("role", "dialog")
		setAttribute("aria-hidden", "true")
		div(classes = setOf("pswp__bg"))
		div(classes = setOf("pswp__scroll-wrap")) {
			div(classes = setOf("pswp__container")) {
				div(classes = setOf("pswp__item"))
				div(classes = setOf("pswp__item"))
				div(classes = setOf("pswp__item"))
			}
			div(classes = setOf("pswp__ui", "pswp__ui--hidden")) {
				div(classes = setOf("pswp__top-bar")) {
					div(classes = setOf("pswp__counter"))
					button("", classes = setOf("pswp__button", "pswp__button--close")) {
						setAttribute("title", "Close (Esc)")
					}
					button("", classes = setOf("pswp__button", "pswp__button--share")) {
						setAttribute("title", "Share")
					}
					button("", classes = setOf("pswp__button", "pswp__button--fs")) {
						setAttribute("title", "Toggle Fullscreen")
					}
					button("", classes = setOf("pswp__button", "pswp__button--zoom")) {
						setAttribute("title", "Zoom in/out")
					}
					div(classes = setOf("pswp__preloader")) {
						div(classes = setOf("pswp__preloader__icn")) {
							div(classes = setOf("pswp__preloader__cut")) {
								div(classes = setOf("pswp__preloader__donut"))
							}
						}
					}
				}
				div(classes = setOf("pswp__share-modal", "pswp__share-modal--hidden", "pswp__single-tap")) {
					div(classes = setOf("pswp__share-tooltip"))
				}
				button("", classes = setOf("pswp__button", "pswp__button--arrow--left")) {
					setAttribute("title", "Previous (arrrow left)")
				}
				button("", classes = setOf("pswp__button", "pswp__button--arrow--right")) {
					setAttribute("title", "Next (arrrow right)")
				}
				div(classes = setOf("pswp__caption")) {
					div(classes = setOf("pswp__caption__center"))
				}
			}
		}
	}

	fun open(items: List<Item>, index: Int? = null, history: Boolean = false) {
		PhotoSwipe(
			elem = getHTMLElementOrThrow(),
			ui = photoswipeUiDefault,
			items = items.toTypedArray(),
			options = jsObject {
				if (index != null) {
					this.index = index
					this.history = history
				}
			}
		).init()
	}
}
