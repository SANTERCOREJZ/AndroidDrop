"""
Drag & drop file sending — the AirDrop-like entry point on the Mac.

Drop any file onto the menu-bar icon and it gets sent to the connected phone.

Rather than overlaying a subview on the status item's button (which would swallow the
clicks that open the menu), we register the button's *window* for dragged types and make
a small delegate object the window's dragging destination. This is Apple's recommended
way to make an NSStatusItem accept drops while keeping normal menu interaction intact.
"""

import objc
from AppKit import NSDragOperationCopy
from Foundation import NSObject, NSURL

# The drag types we accept. "public.file-url" covers file drags from Finder.
_FILE_TYPES = ["public.file-url"]

# NSWindow.delegate is a (zeroing) weak reference, so we must keep our delegate objects
# alive ourselves or they'd be deallocated immediately.
_keep = []


class DropDelegate(NSObject):
    """Window delegate that turns dropped files into a Python callback."""

    def initWithCallback_(self, callback):
        self = objc.super(DropDelegate, self).init()
        if self is None:
            return None
        self._callback = callback
        return self

    def draggingEntered_(self, sender):
        return NSDragOperationCopy

    def prepareForDragOperation_(self, sender):
        return True

    def performDragOperation_(self, sender):
        pb = sender.draggingPasteboard()
        urls = pb.readObjectsForClasses_options_([NSURL], None) or []
        paths = [str(u.path()) for u in urls if u.isFileURL()]
        if paths and self._callback is not None:
            try:
                self._callback(paths)
            except Exception:
                pass
        return bool(paths)


def attach(status_item, on_drop) -> None:
    """
    Make the menu-bar icon accept dropped files, delivering their paths to `on_drop`.

    `on_drop` receives a list of absolute file paths and is called on the AppKit main thread.
    """
    button = status_item.button()
    if button is None:
        return
    win = button.window()
    if win is None:
        return

    delegate = DropDelegate.alloc().initWithCallback_(on_drop)
    _keep.append(delegate)  # retain past this call (window holds only a weak ref)

    win.registerForDraggedTypes_(_FILE_TYPES)
    win.setDelegate_(delegate)
