#pragma once

// OpenGL ES deliberately omits desktop-only tokens, types and entry points.
// Keep their Android declarations in one place so the upstream GL renderer can
// be ported without scattering platform ifdefs through every resource class.
// A token being declared here does not imply the driver supports the feature;
// capabilities.cpp remains the runtime authority.

#include <type_traits>
#include <cstdint>
#include <cstring>

#include <EGL/egl.h>

using GLdouble = double;
using GLclampd = double;

#include <GL/glext.h>

#ifndef GL_TEXTURE_1D
#define GL_TEXTURE_1D 0x0DE0
#endif
#ifndef GL_TEXTURE_BINDING_1D
#define GL_TEXTURE_BINDING_1D 0x8068
#endif
#ifndef GL_DOUBLE
#define GL_DOUBLE 0x140A
#endif
#ifndef GL_COLOR_LOGIC_OP
#define GL_COLOR_LOGIC_OP 0x0BF2
#endif
#ifndef GL_POINT
#define GL_POINT 0x1B00
#endif
#ifndef GL_LINE
#define GL_LINE 0x1B01
#endif
#ifndef GL_FILL
#define GL_FILL 0x1B02
#endif
#ifndef GL_RGBA16
#define GL_RGBA16 0x805B
#endif

// Shader-interpreter bindless access has a recompiler fallback in GLGSRender.
// GLES vendors expose incompatible IMG/NV variants, so ARB calls stay inert
// unless a future dispatch layer can prove the exact ARB contract is present.
inline GLuint64 glGetTextureHandleARB(GLuint)
{
	return 0;
}

inline void glMakeTextureHandleResidentARB(GLuint64)
{
}

inline void glMakeTextureHandleNonResidentARB(GLuint64)
{
}

inline void glProgramUniformHandleui64ARB(GLuint, GLint, GLuint64)
{
}

inline void glProgramUniformHandleui64vARB(GLuint, GLint, GLsizei, const GLuint64*)
{
}

// Fragment outputs are emitted with explicit layout(location=) qualifiers.
// GLES therefore has no equivalent operation to perform at link time.
inline void glBindFragDataLocation(GLuint, GLuint, const GLchar*)
{
}

// EXT_debug_marker is optional and must never be a renderer requirement.
inline PFNGLINSERTEVENTMARKEREXTPROC glInsertEventMarkerEXT = nullptr;

inline void glClearDepth(GLdouble depth)
{
	glClearDepthf(static_cast<GLfloat>(depth));
}

inline void glDepthRange(GLdouble near_value, GLdouble far_value)
{
	glDepthRangef(static_cast<GLfloat>(near_value), static_cast<GLfloat>(far_value));
}

inline void glDepthRangedNV(GLdouble near_value, GLdouble far_value)
{
	glDepthRange(near_value, far_value);
}

inline void glDepthBoundsEXT(GLdouble, GLdouble)
{
}

inline void glDepthBoundsdNV(GLdouble, GLdouble)
{
}

// GLES has neither fixed-function logic operations nor a core polygon-mode
// switch. The renderer capability path keeps optional modes disabled; these
// declarations make the inactive desktop branches compile.
inline void glLogicOp(GLenum)
{
}

inline void glPolygonMode(GLenum, GLenum)
{
}

inline void glTextureBarrier()
{
}

inline void glTextureBarrierNV()
{
}

inline void glVertexAttrib1d(GLuint index, GLdouble x)
{
	glVertexAttrib1f(index, static_cast<GLfloat>(x));
}

inline void glVertexAttrib2d(GLuint index, GLdouble x, GLdouble y)
{
	glVertexAttrib2f(index, static_cast<GLfloat>(x), static_cast<GLfloat>(y));
}

inline void glVertexAttrib3d(GLuint index, GLdouble x, GLdouble y, GLdouble z)
{
	glVertexAttrib3f(index, static_cast<GLfloat>(x), static_cast<GLfloat>(y), static_cast<GLfloat>(z));
}

inline void glVertexAttrib4d(GLuint index, GLdouble x, GLdouble y, GLdouble z, GLdouble w)
{
	glVertexAttrib4f(index, static_cast<GLfloat>(x), static_cast<GLfloat>(y), static_cast<GLfloat>(z), static_cast<GLfloat>(w));
}

inline void glMultiDrawArrays(GLenum mode, const GLint* first, const GLsizei* count, GLsizei draw_count)
{
	for (GLsizei index = 0; index < draw_count; ++index)
	{
		glDrawArrays(mode, first[index], count[index]);
	}
}

inline void glMultiDrawElements(GLenum mode, const GLsizei* count, GLenum type, const void* const* indices, GLsizei draw_count)
{
	for (GLsizei index = 0; index < draw_count; ++index)
	{
		glDrawElements(mode, count[index], type, indices[index]);
	}
}

inline void glPrimitiveRestartIndex(GLuint)
{
}

inline void glGetQueryObjectiv(GLuint query, GLenum name, GLint* value)
{
	GLuint result = 0;
	glGetQueryObjectuiv(query, name, &result);
	*value = static_cast<GLint>(result);
}

namespace gl::gles
{
	inline GLenum buffer_binding_name(GLenum target)
	{
		switch (target)
		{
		case GL_ARRAY_BUFFER: return GL_ARRAY_BUFFER_BINDING;
		case GL_COPY_READ_BUFFER: return GL_COPY_READ_BUFFER_BINDING;
		case GL_COPY_WRITE_BUFFER: return GL_COPY_WRITE_BUFFER_BINDING;
		case GL_ELEMENT_ARRAY_BUFFER: return GL_ELEMENT_ARRAY_BUFFER_BINDING;
		case GL_PIXEL_PACK_BUFFER: return GL_PIXEL_PACK_BUFFER_BINDING;
		case GL_PIXEL_UNPACK_BUFFER: return GL_PIXEL_UNPACK_BUFFER_BINDING;
		case GL_SHADER_STORAGE_BUFFER: return GL_SHADER_STORAGE_BUFFER_BINDING;
		case GL_TRANSFORM_FEEDBACK_BUFFER: return GL_TRANSFORM_FEEDBACK_BUFFER_BINDING;
		case GL_UNIFORM_BUFFER: return GL_UNIFORM_BUFFER_BINDING;
		default: return GL_NONE;
		}
	}

	template <typename Callback>
	inline auto with_buffer(GLuint buffer, GLenum target, Callback&& callback)
	{
		const GLenum binding_name = buffer_binding_name(target);
		GLint previous = 0;
		if (binding_name != GL_NONE)
		{
			glGetIntegerv(binding_name, &previous);
		}
		glBindBuffer(target, buffer);
		if constexpr (std::is_void_v<std::invoke_result_t<Callback>>)
		{
			callback();
			glBindBuffer(target, static_cast<GLuint>(previous));
		}
		else
		{
			auto result = callback();
			glBindBuffer(target, static_cast<GLuint>(previous));
			return result;
		}
	}

	template <typename Callback>
	inline auto with_framebuffer(GLuint framebuffer, Callback&& callback)
	{
		GLint previous = 0;
		glGetIntegerv(GL_FRAMEBUFFER_BINDING, &previous);
		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
		if constexpr (std::is_void_v<std::invoke_result_t<Callback>>)
		{
			callback();
			glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(previous));
		}
		else
		{
			auto result = callback();
			glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(previous));
			return result;
		}
	}

	inline GLenum texture_base_target(GLenum target)
	{
		if (target == GL_TEXTURE_1D)
		{
			return GL_TEXTURE_2D;
		}
		if (target >= GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL_TEXTURE_CUBE_MAP_NEGATIVE_Z)
		{
			return GL_TEXTURE_CUBE_MAP;
		}
		return target;
	}

	inline GLenum texture_binding_name(GLenum target)
	{
		switch (texture_base_target(target))
		{
		case GL_TEXTURE_2D: return GL_TEXTURE_BINDING_2D;
		case GL_TEXTURE_2D_ARRAY: return GL_TEXTURE_BINDING_2D_ARRAY;
		case GL_TEXTURE_2D_MULTISAMPLE: return GL_TEXTURE_BINDING_2D_MULTISAMPLE;
#ifdef GL_TEXTURE_2D_MULTISAMPLE_ARRAY
		case GL_TEXTURE_2D_MULTISAMPLE_ARRAY: return GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY;
#endif
		case GL_TEXTURE_3D: return GL_TEXTURE_BINDING_3D;
		case GL_TEXTURE_CUBE_MAP: return GL_TEXTURE_BINDING_CUBE_MAP;
		case GL_TEXTURE_BUFFER: return GL_TEXTURE_BINDING_BUFFER;
		default: return GL_NONE;
		}
	}

	template <typename Callback>
	inline auto with_texture(GLuint texture, GLenum target, Callback&& callback)
	{
		const GLenum base_target = texture_base_target(target);
		const GLenum binding_name = texture_binding_name(base_target);
		GLint previous = 0;
		if (binding_name != GL_NONE)
		{
			glGetIntegerv(binding_name, &previous);
		}
		glBindTexture(base_target, texture);
		if constexpr (std::is_void_v<std::invoke_result_t<Callback>>)
		{
			callback();
			glBindTexture(base_target, static_cast<GLuint>(previous));
		}
		else
		{
			auto result = callback();
			glBindTexture(base_target, static_cast<GLuint>(previous));
			return result;
		}
	}
}

inline void glNamedBufferStorage(GLuint buffer, GLsizeiptr size, const void* data, GLbitfield)
{
	gl::gles::with_buffer(buffer, GL_COPY_WRITE_BUFFER, [&] { glBufferData(GL_COPY_WRITE_BUFFER, size, data, GL_DYNAMIC_DRAW); });
}

inline void glNamedBufferData(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage)
{
	gl::gles::with_buffer(buffer, GL_COPY_WRITE_BUFFER, [&] { glBufferData(GL_COPY_WRITE_BUFFER, size, data, usage); });
}

inline void glNamedBufferDataEXT(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage)
{
	glNamedBufferData(buffer, size, data, usage);
}

inline void glNamedBufferSubData(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data)
{
	gl::gles::with_buffer(buffer, GL_COPY_WRITE_BUFFER, [&] { glBufferSubData(GL_COPY_WRITE_BUFFER, offset, size, data); });
}

inline void glNamedBufferSubDataEXT(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data)
{
	glNamedBufferSubData(buffer, offset, size, data);
}

inline void glClearNamedBufferSubData(GLuint buffer, GLenum, GLintptr offset, GLsizeiptr size, GLenum, GLenum, const void* data)
{
	gl::gles::with_buffer(buffer, GL_COPY_WRITE_BUFFER, [&]
	{
		auto* mapped = static_cast<GLubyte*>(glMapBufferRange(GL_COPY_WRITE_BUFFER, offset, size, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_RANGE_BIT));
		if (!mapped)
		{
			return;
		}

		const auto* pattern = static_cast<const GLubyte*>(data);
		for (GLsizeiptr index = 0; index < size; ++index)
		{
			mapped[index] = pattern[index & 3];
		}
		glUnmapBuffer(GL_COPY_WRITE_BUFFER);
	});
}

inline void glClearNamedBufferSubDataEXT(GLuint buffer, GLenum internal_format, GLintptr offset, GLsizeiptr size, GLenum format, GLenum type, const void* data)
{
	glClearNamedBufferSubData(buffer, internal_format, offset, size, format, type, data);
}

inline void* glMapNamedBufferRange(GLuint buffer, GLintptr offset, GLsizeiptr size, GLbitfield access)
{
	return gl::gles::with_buffer(buffer, GL_COPY_WRITE_BUFFER, [&] { return glMapBufferRange(GL_COPY_WRITE_BUFFER, offset, size, access); });
}

inline void* glMapNamedBufferRangeEXT(GLuint buffer, GLintptr offset, GLsizeiptr size, GLbitfield access)
{
	return glMapNamedBufferRange(buffer, offset, size, access);
}

inline GLboolean glUnmapNamedBuffer(GLuint buffer)
{
	return gl::gles::with_buffer(buffer, GL_COPY_WRITE_BUFFER, [&] { return glUnmapBuffer(GL_COPY_WRITE_BUFFER); });
}

inline GLboolean glUnmapNamedBufferEXT(GLuint buffer)
{
	return glUnmapNamedBuffer(buffer);
}

inline void glCopyNamedBufferSubData(GLuint read_buffer, GLuint write_buffer, GLintptr read_offset, GLintptr write_offset, GLsizeiptr size)
{
	GLint previous_read = 0;
	GLint previous_write = 0;
	glGetIntegerv(GL_COPY_READ_BUFFER_BINDING, &previous_read);
	glGetIntegerv(GL_COPY_WRITE_BUFFER_BINDING, &previous_write);
	glBindBuffer(GL_COPY_READ_BUFFER, read_buffer);
	glBindBuffer(GL_COPY_WRITE_BUFFER, write_buffer);
	glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, read_offset, write_offset, size);
	glBindBuffer(GL_COPY_READ_BUFFER, static_cast<GLuint>(previous_read));
	glBindBuffer(GL_COPY_WRITE_BUFFER, static_cast<GLuint>(previous_write));
}

inline void glNamedCopyBufferSubDataEXT(GLuint read_buffer, GLuint write_buffer, GLintptr read_offset, GLintptr write_offset, GLsizeiptr size)
{
	glCopyNamedBufferSubData(read_buffer, write_buffer, read_offset, write_offset, size);
}

inline void glTextureSubImage1DEXT(GLuint texture, GLenum, GLint level, GLint x, GLsizei width, GLenum format, GLenum type, const void* data)
{
	gl::gles::with_texture(texture, GL_TEXTURE_2D, [&] { glTexSubImage2D(GL_TEXTURE_2D, level, x, 0, width, 1, format, type, data); });
}

inline void glTextureSubImage1D(GLuint texture, GLint level, GLint x, GLsizei width, GLenum format, GLenum type, const void* data)
{
	glTextureSubImage1DEXT(texture, GL_TEXTURE_1D, level, x, width, format, type, data);
}

inline void glTextureSubImage2DEXT(GLuint texture, GLenum target, GLint level, GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, const void* data)
{
	const GLenum base_target = gl::gles::texture_base_target(target);
	gl::gles::with_texture(texture, base_target, [&] { glTexSubImage2D(target == GL_TEXTURE_1D ? GL_TEXTURE_2D : target, level, x, y, width, height, format, type, data); });
}

inline void glTextureSubImage2D(GLuint texture, GLint level, GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, const void* data)
{
	glTextureSubImage2DEXT(texture, GL_TEXTURE_2D, level, x, y, width, height, format, type, data);
}

inline void glTextureSubImage3DEXT(GLuint texture, GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void* data)
{
	gl::gles::with_texture(texture, target, [&] { glTexSubImage3D(target, level, x, y, z, width, height, depth, format, type, data); });
}

inline void glTextureSubImage3D(GLuint texture, GLint level, GLint x, GLint y, GLint z, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void* data)
{
	// Core DSA does not carry a target. In this renderer this path is used for
	// 3D/array images and cubemaps; the GLES capability setup selects the EXT
	// path for ordinary resources. Cubemap uploads are handled below per face.
	gl::gles::with_texture(texture, GL_TEXTURE_3D, [&] { glTexSubImage3D(GL_TEXTURE_3D, level, x, y, z, width, height, depth, format, type, data); });
}

inline void glTextureBufferRange(GLuint texture, GLenum internal_format, GLuint buffer, GLintptr offset, GLsizeiptr size)
{
	gl::gles::with_texture(texture, GL_TEXTURE_BUFFER, [&] { glTexBufferRange(GL_TEXTURE_BUFFER, internal_format, buffer, offset, size); });
}

inline void glTextureBufferRangeEXT(GLuint texture, GLenum, GLenum internal_format, GLuint buffer, GLintptr offset, GLsizeiptr size)
{
	glTextureBufferRange(texture, internal_format, buffer, offset, size);
}

inline void glTextureParameteri(GLuint texture, GLenum name, GLint value)
{
	gl::gles::with_texture(texture, GL_TEXTURE_2D, [&] { glTexParameteri(GL_TEXTURE_2D, name, value); });
}

inline void glTextureParameteriEXT(GLuint texture, GLenum target, GLenum name, GLint value)
{
	const GLenum base_target = gl::gles::texture_base_target(target);
	gl::gles::with_texture(texture, base_target, [&] { glTexParameteri(base_target, name, value); });
}

inline void glTextureParameteriv(GLuint texture, GLenum name, const GLint* value)
{
	gl::gles::with_texture(texture, GL_TEXTURE_2D, [&] { glTexParameteriv(GL_TEXTURE_2D, name, value); });
}

inline void glTextureParameterivEXT(GLuint texture, GLenum target, GLenum name, const GLint* value)
{
	const GLenum base_target = gl::gles::texture_base_target(target);
	gl::gles::with_texture(texture, base_target, [&] { glTexParameteriv(base_target, name, value); });
}

namespace gl::gles
{
	inline GLsizei pixel_size(GLenum format, GLenum type)
	{
		switch (type)
		{
		case GL_UNSIGNED_BYTE_3_3_2:
		case GL_UNSIGNED_BYTE_2_3_3_REV:
			return 1;
		case GL_UNSIGNED_SHORT_5_6_5:
		case GL_UNSIGNED_SHORT_5_6_5_REV:
		case GL_UNSIGNED_SHORT_4_4_4_4:
		case GL_UNSIGNED_SHORT_4_4_4_4_REV:
		case GL_UNSIGNED_SHORT_5_5_5_1:
		case GL_UNSIGNED_SHORT_1_5_5_5_REV:
			return 2;
		case GL_UNSIGNED_INT_8_8_8_8:
		case GL_UNSIGNED_INT_8_8_8_8_REV:
		case GL_UNSIGNED_INT_10_10_10_2:
		case GL_UNSIGNED_INT_2_10_10_10_REV:
		case GL_UNSIGNED_INT_24_8:
			return 4;
		case GL_FLOAT_32_UNSIGNED_INT_24_8_REV:
			return 8;
		default:
			break;
		}

		GLsizei components = 1;
		if (format == GL_RG) components = 2;
		else if (format == GL_RGB || format == GL_BGR) components = 3;
		else if (format == GL_RGBA || format == GL_BGRA) components = 4;

		GLsizei component_size = 1;
		if (type == GL_SHORT || type == GL_UNSIGNED_SHORT || type == GL_HALF_FLOAT) component_size = 2;
		else if (type == GL_INT || type == GL_UNSIGNED_INT || type == GL_FLOAT) component_size = 4;
		else if (type == GL_DOUBLE) component_size = 8;
		return components * component_size;
	}

	inline void attach_read_layer(GLenum target, GLenum attachment, GLint level, GLint layer)
	{
		if (target == GL_TEXTURE_CUBE_MAP)
		{
			glFramebufferTexture2D(GL_READ_FRAMEBUFFER, attachment, GL_TEXTURE_CUBE_MAP_POSITIVE_X + layer, 0, level);
		}
		else if (target == GL_TEXTURE_3D || target == GL_TEXTURE_2D_ARRAY)
		{
			glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, attachment, 0, level, layer);
		}
	}

	inline void get_texture_image(GLuint texture, GLenum target, GLint level, GLenum format, GLenum type, void* data)
	{
		target = texture_base_target(target);
		const GLenum query_target = target == GL_TEXTURE_CUBE_MAP ? GL_TEXTURE_CUBE_MAP_POSITIVE_X : target;
		GLint width = 1;
		GLint height = 1;
		GLint depth = 1;
		with_texture(texture, target, [&]
		{
			glGetTexLevelParameteriv(query_target, level, GL_TEXTURE_WIDTH, &width);
			glGetTexLevelParameteriv(query_target, level, GL_TEXTURE_HEIGHT, &height);
			if (target == GL_TEXTURE_3D || target == GL_TEXTURE_2D_ARRAY)
			{
				glGetTexLevelParameteriv(query_target, level, GL_TEXTURE_DEPTH, &depth);
			}
		});
		if (target == GL_TEXTURE_CUBE_MAP) depth = 6;

		GLint previous_fbo = 0;
		glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previous_fbo);
		GLuint fbo = 0;
		glGenFramebuffers(1, &fbo);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);

		GLenum attachment = GL_COLOR_ATTACHMENT0;
		if (format == GL_DEPTH_COMPONENT) attachment = GL_DEPTH_ATTACHMENT;
		else if (format == GL_STENCIL_INDEX) attachment = GL_STENCIL_ATTACHMENT;
		else if (format == GL_DEPTH_STENCIL) attachment = GL_DEPTH_STENCIL_ATTACHMENT;

		if (target != GL_TEXTURE_CUBE_MAP && target != GL_TEXTURE_3D && target != GL_TEXTURE_2D_ARRAY)
		{
			glFramebufferTexture2D(GL_READ_FRAMEBUFFER, attachment, target, texture, level);
		}
		if (attachment == GL_COLOR_ATTACHMENT0) glReadBuffer(GL_COLOR_ATTACHMENT0);
		else glReadBuffer(GL_NONE);

		GLint row_length = 0;
		GLint alignment = 4;
		glGetIntegerv(GL_PACK_ROW_LENGTH, &row_length);
		glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
		const std::uintptr_t row_bytes = static_cast<std::uintptr_t>(row_length > 0 ? row_length : width) * pixel_size(format, type);
		const std::uintptr_t stride = (row_bytes + static_cast<std::uintptr_t>(alignment - 1)) & ~static_cast<std::uintptr_t>(alignment - 1);
		const std::uintptr_t layer_bytes = stride * static_cast<std::uintptr_t>(height);
		const std::uintptr_t base = reinterpret_cast<std::uintptr_t>(data);

		for (GLint layer = 0; layer < depth; ++layer)
		{
			if (target == GL_TEXTURE_CUBE_MAP)
			{
				glFramebufferTexture2D(GL_READ_FRAMEBUFFER, attachment, GL_TEXTURE_CUBE_MAP_POSITIVE_X + layer, texture, level);
			}
			else if (target == GL_TEXTURE_3D || target == GL_TEXTURE_2D_ARRAY)
			{
				glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, attachment, texture, level, layer);
			}
			glReadPixels(0, 0, width, height, format, type, reinterpret_cast<void*>(base + layer_bytes * static_cast<std::uintptr_t>(layer)));
		}

		glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(previous_fbo));
		glDeleteFramebuffers(1, &fbo);
	}
}

inline void glGetTextureImageEXT(GLuint texture, GLenum target, GLint level, GLenum format, GLenum type, void* data)
{
	gl::gles::get_texture_image(texture, target, level, format, type, data);
}

inline void glGetTextureImage(GLuint texture, GLint level, GLenum format, GLenum type, GLsizei, void* data)
{
	gl::gles::get_texture_image(texture, GL_TEXTURE_2D, level, format, type, data);
}

inline void glGetTextureSubImage(GLuint texture, GLint level, GLint x, GLint y, GLint, GLsizei width, GLsizei height, GLsizei, GLenum format, GLenum type, GLsizei, void* data)
{
	GLint previous_fbo = 0;
	glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previous_fbo);
	GLuint fbo = 0;
	glGenFramebuffers(1, &fbo);
	glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
	glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, level);
	glReadBuffer(GL_COLOR_ATTACHMENT0);
	glReadPixels(x, y, width, height, format, type, data);
	glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(previous_fbo));
	glDeleteFramebuffers(1, &fbo);
}

inline void glTextureView(GLuint texture, GLenum target, GLuint original, GLenum internal_format, GLuint min_level, GLuint num_levels, GLuint min_layer, GLuint num_layers)
{
	using texture_view_proc = void (GL_APIENTRYP)(GLuint, GLenum, GLuint, GLenum, GLuint, GLuint, GLuint, GLuint);
	static const auto supported_variant = []
	{
		GLint count = 0;
		glGetIntegerv(GL_NUM_EXTENSIONS, &count);
		for (GLint index = 0; index < count; ++index)
		{
			const auto* extension = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(index)));
			if (!extension)
			{
				continue;
			}
			if (std::strcmp(extension, "GL_OES_texture_view") == 0)
			{
				return 1;
			}
			if (std::strcmp(extension, "GL_EXT_texture_view") == 0)
			{
				return 2;
			}
		}
		return 0;
	}();
	static const auto texture_view_oes = supported_variant == 1
		? reinterpret_cast<texture_view_proc>(eglGetProcAddress("glTextureViewOES"))
		: nullptr;
	static const auto texture_view_ext = supported_variant == 2
		? reinterpret_cast<texture_view_proc>(eglGetProcAddress("glTextureViewEXT"))
		: nullptr;
	const GLenum gles_target = gl::gles::texture_base_target(target);
	if (texture_view_oes) texture_view_oes(texture, gles_target, original, internal_format, min_level, num_levels, min_layer, num_layers);
	else if (texture_view_ext) texture_view_ext(texture, gles_target, original, internal_format, min_level, num_levels, min_layer, num_layers);
}

inline void glCompressedTextureSubImage1DEXT(GLuint texture, GLenum, GLint level, GLint x, GLsizei width, GLenum format, GLsizei size, const void* data)
{
	gl::gles::with_texture(texture, GL_TEXTURE_2D, [&] { glCompressedTexSubImage2D(GL_TEXTURE_2D, level, x, 0, width, 1, format, size, data); });
}

inline void glCompressedTextureSubImage1D(GLuint texture, GLint level, GLint x, GLsizei width, GLenum format, GLsizei size, const void* data)
{
	glCompressedTextureSubImage1DEXT(texture, GL_TEXTURE_1D, level, x, width, format, size, data);
}

inline void glCompressedTextureSubImage2DEXT(GLuint texture, GLenum target, GLint level, GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLsizei size, const void* data)
{
	gl::gles::with_texture(texture, target, [&] { glCompressedTexSubImage2D(target, level, x, y, width, height, format, size, data); });
}

inline void glCompressedTextureSubImage2D(GLuint texture, GLint level, GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLsizei size, const void* data)
{
	glCompressedTextureSubImage2DEXT(texture, GL_TEXTURE_2D, level, x, y, width, height, format, size, data);
}

inline void glCompressedTextureSubImage3DEXT(GLuint texture, GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei size, const void* data)
{
	gl::gles::with_texture(texture, target, [&] { glCompressedTexSubImage3D(target, level, x, y, z, width, height, depth, format, size, data); });
}

inline void glCompressedTextureSubImage3D(GLuint texture, GLint level, GLint x, GLint y, GLint z, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLsizei size, const void* data)
{
	glCompressedTextureSubImage3DEXT(texture, GL_TEXTURE_3D, level, x, y, z, width, height, depth, format, size, data);
}

inline void glNamedFramebufferTexture(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level)
{
	gl::gles::with_framebuffer(framebuffer, [&] { glFramebufferTexture(GL_FRAMEBUFFER, attachment, texture, level); });
}

inline void glNamedFramebufferTexture2DCompat(GLuint framebuffer, GLenum attachment, GLenum target, GLuint texture, GLint level)
{
	gl::gles::with_framebuffer(framebuffer, [&]
	{
		glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, target, texture, level);
	});
}

inline void glNamedFramebufferTextureEXT(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level)
{
	glNamedFramebufferTexture(framebuffer, attachment, texture, level);
}

inline void glNamedFramebufferTextureLayer(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level, GLint layer)
{
	gl::gles::with_framebuffer(framebuffer, [&] { glFramebufferTextureLayer(GL_FRAMEBUFFER, attachment, texture, level, layer); });
}

inline void glNamedFramebufferTextureLayerEXT(GLuint framebuffer, GLenum attachment, GLuint texture, GLint level, GLint layer)
{
	glNamedFramebufferTextureLayer(framebuffer, attachment, texture, level, layer);
}

inline void glNamedFramebufferDrawBuffers(GLuint framebuffer, GLsizei count, const GLenum* buffers)
{
	gl::gles::with_framebuffer(framebuffer, [&] { glDrawBuffers(count, buffers); });
}

inline void glFramebufferDrawBuffersEXT(GLuint framebuffer, GLsizei count, const GLenum* buffers)
{
	glNamedFramebufferDrawBuffers(framebuffer, count, buffers);
}

inline void glNamedFramebufferReadBuffer(GLuint framebuffer, GLenum buffer)
{
	gl::gles::with_framebuffer(framebuffer, [&] { glReadBuffer(buffer); });
}

inline void glFramebufferReadBufferEXT(GLuint framebuffer, GLenum buffer)
{
	glNamedFramebufferReadBuffer(framebuffer, buffer);
}

// Adapt ARMSX3's DSA shim to preserve the read framebuffer binding as well.
inline void glNamedFramebufferParameteri(GLuint framebuffer, GLenum pname, GLint param)
{
	GLint previous = 0;
	glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previous);
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
	glFramebufferParameteri(GL_DRAW_FRAMEBUFFER, pname, param);
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(previous));
}

inline void glNamedFramebufferParameteriEXT(GLuint framebuffer, GLenum pname, GLint param)
{
	glNamedFramebufferParameteri(framebuffer, pname, param);
}

inline GLenum glCheckNamedFramebufferStatus(GLuint framebuffer, GLenum target)
{
	return gl::gles::with_framebuffer(framebuffer, [&] { return glCheckFramebufferStatus(target); });
}

inline GLenum glCheckNamedFramebufferStatusEXT(GLuint framebuffer, GLenum target)
{
	return glCheckNamedFramebufferStatus(framebuffer, target);
}

// No GLES equivalent exists. These legacy helpers have no callers in the RSX
// renderer; texture uploads use glTexSubImage* paths instead.
inline void glDrawPixels(GLsizei, GLsizei, GLenum, GLenum, const void*)
{
}
