#include "stdafx.h"
#include "ex.h"

namespace gl::ex
{
	void glNamedBufferStorageEX(GLuint buffer, GLenum target, GLsizeiptr size, const void* data, GLbitfield flags)
	{
#ifdef RSX_GLES
		// GLES core has no immutable buffer storage. This path is only a safety
		// fallback because the GLES capability table keeps buffer_storage off.
		// Preserve the caller's binding and provide equivalent mutable storage.
		gl::gles::with_buffer(buffer, target, [&]
		{
			glBufferData(target, size, data, (flags & GL_DYNAMIC_STORAGE_BIT) ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW);
		});
#else
		GLuint restore = GL_NONE;
		glGetIntegerv(target, utils::bless<GLint>(&restore));

		glBindBuffer(target, buffer);
		glBufferStorage(target, size, data, flags);

		if (restore != buffer)
		{
			glBindBuffer(target, restore);
		}
#endif
	}
}
