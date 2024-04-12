
use std::io::{ErrorKind, Read, Write};

use anyhow::{anyhow, Context, Result};
use async_trait::async_trait;
use byteorder::BigEndian;
use tokio::io::{AsyncRead, AsyncWrite};


pub trait WriteFramed {
	fn write_framed(&mut self, msg: impl AsRef<[u8]>) -> Result<()>;
}

impl<W> WriteFramed for W
	where
		W: Write
{
	fn write_framed(&mut self, msg: impl AsRef<[u8]>) -> Result<()> {

		use byteorder::WriteBytesExt;

		let msg = msg.as_ref();

		// prepend the size of the message
		let size: u32 = msg.len()
			.try_into()
			.map_err(|_| anyhow!("Message too large: {} bytes, max of {} bytes", msg.len(), u32::MAX))?;
		self.write_u32::<BigEndian>(size)
			.context("Failed to write size")?;

		// send the message itself
		self.write_all(msg)
			.context("Failed to write message")?;

		Ok(())
	}
}


pub trait ReadFramed {
	fn read_framed(&mut self) -> Result<Vec<u8>>;
}

impl<R> ReadFramed for R
	where
		R: Read
{
	fn read_framed(&mut self) -> Result<Vec<u8>> {

		use byteorder::ReadBytesExt;

		// read the size of the next message
		let size = self.read_u32::<BigEndian>()
			.context("Failed to read message size")?;

		// allocate a buffer
		let mut msg = vec![0u8; size as usize];

		// read the message
		self.read_exact(msg.as_mut())
			.context("Failed to read message")?;

		Ok(msg)
	}
}


#[async_trait]
pub trait AsyncWriteFramed {
	async fn write_framed(&mut self, msg: impl AsRef<[u8]> + Send) -> Result<()>;
}

#[async_trait]
impl<W> AsyncWriteFramed for W
	where
		W: AsyncWrite + Unpin + Send
{
	async fn write_framed(&mut self, msg: impl AsRef<[u8]> + Send) -> Result<()> {

		use tokio::io::AsyncWriteExt;

		let msg = msg.as_ref();

		// prepend the size of the message
		let size: u32 = msg.len()
			.try_into()
			.map_err(|_| anyhow!("Message too large: {} bytes, max of {} bytes", msg.len(), u32::MAX))?;
		self.write_u32(size)
			.await
			.context("Failed to write size")?;

		// send the message itself
		self.write_all(msg)
			.await
			.context("Failed to write message")?;

		Ok(())
	}
}


#[async_trait]
pub trait AsyncReadFramed {
	async fn read_framed(&mut self) -> Result<Option<Vec<u8>>>;
}

#[async_trait]
impl<R> AsyncReadFramed for R
	where
		R: AsyncRead + Unpin + Send
{
	async fn read_framed(&mut self) -> Result<Option<Vec<u8>>> {

		use tokio::io::AsyncReadExt;

		// read the size of the next message
		let result = self.read_u32()
			.await;
		let size = match result {
			Ok(size) => size,
			Err(e) if e.kind() == ErrorKind::UnexpectedEof => return Ok(None),
			r => r.context("Failed to read message size")?
		};

		// allocate a buffer
		let mut msg = vec![0u8; size as usize];

		// read the message
		self.read_exact(msg.as_mut())
			.await
			.context("Failed to read message")?;

		Ok(Some(msg))
	}
}
