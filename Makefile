VERSION = 1.1
BINDIR = /usr/bin
MANDIR = /usr/share/man
DOCDIR = /usr/share/doc/evdisk
ICONDIR = /usr/share/icons/hicolor

SED_EVDISK = $(shell echo $(BINDIR)/evdisk | sed  s/\\//\\\\\\\\\\//g)
SED_ICONDIR =  $(shell echo $(ICONDIR) | sed  s/\\//\\\\\\\\\\//g)

APPS_DIR = apps
SYS_APPDIR = /usr/share/applications
SYS_ICON_DIR = /usr/share/icons/hicolor
SYS_APP_ICON_DIR = $(SYS_ICON_DIR)/scalable/$(APPS_DIR)

APPDIR = $(DESTDIR)$(SYS_APPDIR)
ICON_DIR = $(DESTDIR)$(SYS_ICON_DIR)
APP_ICON_DIR = $(DESTDIR)$(SYS_APP_ICON_DIR)

SOURCEICON = evdisk.svg
TARGETICON = evdisk.svg
TARGETICON_PNG = evdisk.png

ICON_WIDTHS = 16 20 22 24 32 36 48 64 72 96 128 192 256

# use 'make deb' first to set up the icons if those should be tested
test:
	rm -f evdisk
	make BINDIR=$(shell pwd) \
		ICONDIR=$(shell pwd)/BUILD/usr/share/icons/hicolor  evdisk

evdisk: evdisk.sh
	sed -e s/EVDISK/$(SED_EVDISK)/ evdisk.sh | \
	sed -e s/ICONDIR/$(SED_ICONDIR)/ > evdisk
	chmod u+x evdisk

install: evdisk
	install -d $(DESTDIR)$(MANDIR)/man1
	install -d $(DESTDIR)$(DOCDIR)
	install -d $(DESTDIR)$(BINDIR)
	install -d $(APP_ICON_DIR)
	install -d $(APPDIR)
	install -m 0755 -T evdisk $(DESTDIR)$(BINDIR)/evdisk
	sed -e s/VERSION/$(VERSION)/ evdisk.1 | gzip -n -9 > evdisk.1.gz
	install -m 0644 evdisk.1.gz $(DESTDIR)$(MANDIR)/man1
	rm evdisk.1.gz
	install -m 0644 -T $(SOURCEICON) $(APP_ICON_DIR)/$(TARGETICON)
	for i in $(ICON_WIDTHS) ; do \
		install -d $(ICON_DIR)/$${i}x$${i}/$(APPS_DIR) ; \
		inkscape -w $$i -e tmp.png $(SOURCEICON) ; \
		install -m 0644 -T tmp.png \
			$(ICON_DIR)/$${i}x$${i}/$(APPS_DIR)/$(TARGETICON_PNG); \
		rm tmp.png ; \
	done
	install -m 0644 evdisk.desktop $(APPDIR)
	gzip -n -9 < changelog > changelog.gz
	install -m 0644 changelog.gz $(DESTDIR)$(DOCDIR)
	rm changelog.gz
	gzip -n -9 < changelog.Debian > changelog.Debian.gz
	install -m 0644 changelog.Debian.gz $(DESTDIR)$(DOCDIR)
	install -m 0644 copyright $(DESTDIR)$(DOCDIR)
	rm changelog.Debian.gz

DEB = evdisk_$(VERSION)_all.deb

deb: $(DEB)

$(DEB): control copyright changelog changelog.Debian postinst postrm \
		evdisk.sh evdisk.1 evdisk.desktop evdisk.svg Makefile
	mkdir -p BUILD
	(cd BUILD ; rm -rf usr DEBIAN)
	mkdir -p BUILD/DEBIAN
	cp postinst BUILD/DEBIAN/postinst
	chmod a+x BUILD/DEBIAN/postinst
	cp postrm BUILD/DEBIAN/postrm
	chmod a+x BUILD/DEBIAN/postrm
	rm evdisk
	$(MAKE) install DESTDIR=BUILD
	sed -e s/VERSION/$(VERSION)/ control > BUILD/DEBIAN/control
	fakeroot dpkg-deb --build BUILD
	mv BUILD.deb $(DEB)
