# Privacy Policy — Budget Companion

**Last updated: 2026-08-03**

Budget Companion is a companion to the Budget app for Nextcloud. This
policy covers the Android app only.

## Short version

The developer of this app collects nothing. There is no analytics SDK, no
advertising SDK, no crash reporter, and no tracking of any kind built into
the app. The only network traffic the app generates is between your device
and the Nextcloud server you connect it to — a server you own or control,
not one operated by the developer.

## What data the app handles, and where it goes

- **Receipt photos.** Taken with your device's camera, or picked from your
  gallery, or received via Android's share sheet from another app. A photo
  is stored in the app's private storage on your device until it has been
  processed, then sent to **your own Nextcloud server** for extraction and,
  once you save the transaction, attached to it there. The app never sends a
  photo anywhere except the Nextcloud server you configured.
- **Transaction data** (merchant, date, amount, category, account). Read
  from and written to your Nextcloud server. Nothing is sent elsewhere.
- **Your Nextcloud credentials.** The app authenticates using Nextcloud's
  Login Flow v2, which issues an app-specific password — you never type your
  main Nextcloud password into this app. The resulting credential is stored
  only on your device, in Android's encrypted shared preferences (backed by
  the platform keystore), and is never transmitted anywhere except in
  authenticated requests to your own server.

That's the complete list. The app has no account system of its own, no
backend operated by the developer, and no server-side component that ever
sees your data.

## What the app does not do

- It does not use Google Play Services, Firebase, or any Google Mobile
  Services component.
- It does not include any analytics, advertising, or tracking library.
- It does not collect device identifiers, usage statistics, or crash reports
  and send them to the developer.
- It does not share data with any third party, because it has no third
  party in its data path at all — only your device and your Nextcloud
  server.
- It does not know, and does not ask, which extraction backend your Budget
  server is configured to use. That is entirely your server's concern.

## Data in transit

Requests to your Nextcloud server use HTTPS by default. Plain HTTP is
supported only as a deliberate accommodation for self-hosting (for example,
a Nextcloud instance on your home network without a public certificate),
and it is doubly gated: the app accepts an `http://` address only when it
resolves to a private/LAN address, and even then it will not connect until
you have confirmed an explicit in-app warning that everything — your
password, your receipts, and every amount — travels unencrypted on that
network. Public-hostname connections are required to use HTTPS.

The server address returned by Nextcloud's login flow is held to the same
policy: if you signed in over HTTPS and the server reports back a plain
HTTP address, sign-in is stopped rather than silently storing an
unencrypted endpoint.

## Data deletion

Uninstalling the app removes the locally stored credential, the offline
capture queue, and any cached photos. Because the developer never held a
copy of your data, there is nothing further to delete on request — your
data continues to exist only where it always did: on your own Nextcloud
server, under your own control.

## Changes to this policy

If this policy changes, the updated version will be published at this same
location with a new "Last updated" date.

## Contact

For questions about this policy, contact the developer at
adam@otherworld.dev.
