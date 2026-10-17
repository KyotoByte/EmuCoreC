#include "stdafx.h"
#include "OpenGL.h"

#ifdef __ANDROID__

#include <EGL/egl.h>
#include <android/native_window.h>

#include <mutex>

namespace gl::es
{
	namespace
	{
		struct context
		{
			EGLContext handle = EGL_NO_CONTEXT;
			EGLSurface surface = EGL_NO_SURFACE;
			ANativeWindow* window = nullptr;
			bool offscreen = false;
		};

		std::mutex g_egl_mutex;
		EGLDisplay g_display = EGL_NO_DISPLAY;
		EGLConfig g_config = nullptr;
		u32 g_context_count = 0;

		[[noreturn]] void fail(const char* operation)
		{
			fmt::throw_exception("%s failed with EGL error 0x%x", operation, eglGetError());
		}

		void initialize_display()
		{
			if (g_display != EGL_NO_DISPLAY)
			{
				return;
			}

			g_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
			if (g_display == EGL_NO_DISPLAY || !eglInitialize(g_display, nullptr, nullptr))
			{
				g_display = EGL_NO_DISPLAY;
				fail("eglInitialize");
			}

			if (!eglBindAPI(EGL_OPENGL_ES_API))
			{
				fail("eglBindAPI(EGL_OPENGL_ES_API)");
			}

			const EGLint attributes[] = {
				EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
				EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
				EGL_RED_SIZE, 8,
				EGL_GREEN_SIZE, 8,
				EGL_BLUE_SIZE, 8,
				EGL_ALPHA_SIZE, 0,
				EGL_DEPTH_SIZE, 0,
				EGL_STENCIL_SIZE, 0,
				EGL_NONE,
			};

			EGLint count = 0;
			if (!eglChooseConfig(g_display, attributes, &g_config, 1, &count) || count != 1)
			{
				fail("eglChooseConfig(GLES 3)");
			}
		}

		EGLSurface create_window_surface(context& value, ANativeWindow* window)
		{
			if (!window)
			{
				fmt::throw_exception("Cannot create an EGL window surface without ANativeWindow");
			}

			EGLint native_format = 0;
			if (!eglGetConfigAttrib(g_display, g_config, EGL_NATIVE_VISUAL_ID, &native_format))
			{
				fail("eglGetConfigAttrib(EGL_NATIVE_VISUAL_ID)");
			}

			ANativeWindow_setBuffersGeometry(window, 0, 0, native_format);
			EGLSurface result = eglCreateWindowSurface(g_display, g_config, window, nullptr);
			if (result == EGL_NO_SURFACE)
			{
				fail("eglCreateWindowSurface");
			}

			ANativeWindow_acquire(window);
			value.window = window;
			return result;
		}

		void release_surface(context& value)
		{
			if (value.surface != EGL_NO_SURFACE)
			{
				eglDestroySurface(g_display, value.surface);
				value.surface = EGL_NO_SURFACE;
			}

			if (value.window)
			{
				ANativeWindow_release(value.window);
				value.window = nullptr;
			}
		}
	}

	void* create_context(void* native_window, void* share_context)
	{
		std::lock_guard lock(g_egl_mutex);
		initialize_display();

		auto result = std::make_unique<context>();
		const auto* shared = static_cast<const context*>(share_context);
		const EGLContext shared_handle = shared ? shared->handle : EGL_NO_CONTEXT;
		const EGLint attributes[] = {
			EGL_CONTEXT_MAJOR_VERSION, 3,
			EGL_CONTEXT_MINOR_VERSION, 2,
			EGL_NONE,
		};

		result->handle = eglCreateContext(g_display, g_config, shared_handle, attributes);
		if (result->handle == EGL_NO_CONTEXT)
		{
			fail("eglCreateContext(GLES 3.2)");
		}

		if (shared)
		{
			const EGLint pbuffer_attributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
			result->surface = eglCreatePbufferSurface(g_display, g_config, pbuffer_attributes);
			result->offscreen = true;
			if (result->surface == EGL_NO_SURFACE)
			{
				eglDestroyContext(g_display, result->handle);
				fail("eglCreatePbufferSurface");
			}
		}
		else
		{
			result->surface = create_window_surface(*result, static_cast<ANativeWindow*>(native_window));
		}

		++g_context_count;
		return result.release();
	}

	void destroy_context(void* opaque)
	{
		if (!opaque)
		{
			return;
		}

		std::lock_guard lock(g_egl_mutex);
		std::unique_ptr<context> value(static_cast<context*>(opaque));

		if (eglGetCurrentContext() == value->handle)
		{
			eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
		}

		release_surface(*value);
		if (value->handle != EGL_NO_CONTEXT)
		{
			eglDestroyContext(g_display, value->handle);
		}

		if (g_context_count > 0 && --g_context_count == 0)
		{
			eglTerminate(g_display);
			g_display = EGL_NO_DISPLAY;
			g_config = nullptr;
		}
	}

	void make_current(void* opaque, void* native_window)
	{
		auto* value = static_cast<context*>(opaque);
		if (!value)
		{
			fmt::throw_exception("Null EGL context passed to make_current");
		}

		std::lock_guard lock(g_egl_mutex);
		if (!value->offscreen && value->window != native_window)
		{
			if (eglGetCurrentContext() == value->handle)
			{
				eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
			}
			release_surface(*value);
			value->surface = create_window_surface(*value, static_cast<ANativeWindow*>(native_window));
		}

		if (!eglMakeCurrent(g_display, value->surface, value->surface, value->handle))
		{
			fail("eglMakeCurrent");
		}
	}

	void swap_buffers()
	{
		const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
		if (surface == EGL_NO_SURFACE || !eglSwapBuffers(g_display, surface))
		{
			fail("eglSwapBuffers");
		}
	}
}

#endif
