// Lets Chrome install the page as an app. It keeps nothing offline: the remote needs the TV anyway,
// so every request goes to the network as usual.
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });
