package main

import (
	"bufio"
	"crypto/rand"
	"fmt"
	"io"
	"os"
	"sync"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

type signallingReader struct {
	io.Reader
	once    sync.Once
	reading chan struct{}
}

func (r *signallingReader) Read(p []byte) (int, error) {
	r.once.Do(func() { close(r.reading) })
	return r.Reader.Read(p)
}

// Exercise the actual helper-side Windows handle. Stdin/stdout and mock
// transports have independent read/write paths and miss this failure.
func TestLifecyclePipeReportsWhileWaitingForParentCommand(t *testing.T) {
	for _, status := range []string{peerBackLine, "SERVER-STOPPED", "SERVER-UNAVAILABLE"} {
		t.Run(status, func(t *testing.T) { checkIdleCommandChannel(t, status) })
	}
}

func checkIdleCommandChannel(t *testing.T, status string) {
	var id [16]byte
	rand.Read(id[:])
	name := fmt.Sprintf("linqavo-wg-%x", id)
	path, err := windows.UTF16PtrFromString(`\\.\pipe\` + name)
	if err != nil {
		t.Fatal(err)
	}
	handle, err := windows.CreateNamedPipe(path, windows.PIPE_ACCESS_DUPLEX|windows.FILE_FLAG_OVERLAPPED,
		windows.PIPE_REJECT_REMOTE_CLIENTS, 1, 4096, 4096, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	parent := os.NewFile(uintptr(handle), name)
	defer parent.Close()
	previous := expectedParentPID
	expectedParentPID = uint(os.Getpid())
	defer func() { expectedParentPID = previous }()
	helper, err := openChannel(name)
	if err != nil {
		t.Fatal(err)
	}
	reader := &signallingReader{Reader: helper, reading: make(chan struct{})}
	done := make(chan struct{})
	go func() {
		defer close(done)
		waitForShutdown(bufio.NewReader(reader), false, nil, func(string) {})
	}()
	defer func() {
		parent.Close() // release the command read even on a failed assertion
		helper.Close()
		<-done
	}()
	<-reader.reading
	// Allow the command read to become pending in the kernel before writing.
	time.Sleep(50 * time.Millisecond)
	if err := parent.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	written := make(chan error, 1)
	go func() {
		_, err := fmt.Fprintln(helper, status)
		written <- err
	}()
	line, err := bufio.NewReader(parent).ReadString('\n')
	if err != nil || line != status+"\n" {
		t.Fatalf("waiting for %s with an idle command channel: line=%q err=%v", status, line, err)
	}
	if err := <-written; err != nil {
		t.Fatal(err)
	}
}
